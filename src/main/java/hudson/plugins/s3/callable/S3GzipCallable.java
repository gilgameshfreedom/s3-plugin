package hudson.plugins.s3.callable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.plugins.s3.Destination;
import hudson.plugins.s3.MD5;
import hudson.plugins.s3.Uploads;
import hudson.util.Secret;
import org.apache.commons.io.IOUtils;
import software.amazon.awssdk.transfer.s3.model.Upload;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

public final class S3GzipCallable extends S3BaseUploadCallable implements MasterSlaveCallable<String> {
    public S3GzipCallable(String accessKey, Secret secretKey, boolean useRole, Destination dest, Map<String, String> userMetadata, String storageClass, String selregion, boolean useServerSideEncryption, ProxyConfiguration proxy, boolean usePathStyle) {
        super(accessKey, secretKey, useRole, dest, userMetadata, storageClass, selregion, useServerSideEncryption, proxy, usePathStyle);
    }

    // Return a File containing the gzipped contents of the input file.
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    private File gzipFile(FilePath file) throws IOException, InterruptedException {
        final File localFile = File.createTempFile("s3plugin", ".bin");
        try (InputStream inputStream = file.read()) {
            try (OutputStream outputStream = new FileOutputStream(localFile)) {
                try (OutputStream gzipStream = new GZIPOutputStream(outputStream, true)) {
                    IOUtils.copy(inputStream, gzipStream);
                    gzipStream.flush();
                }
            }
        } catch (RuntimeException ex) {
            localFile.delete();
            throw ex;
        }
        return localFile;
    }

    // Hook to ensure that the file is deleted once the upload finishes.
    private static class CleanupHook implements TransferListener {
        private final File localFile;

        CleanupHook(File localFile) {
            this.localFile = localFile;
        }

        @Override
        public void transferComplete(Context.TransferComplete context) {
            done(context);
        }

        @Override
        public void transferFailed(Context.TransferFailed context) {
            TransferListener.super.transferFailed(context);
        }

        public void done(Context.TransferComplete context) {
            if (localFile.delete()) {
                Logger.getLogger(S3GzipCallable.class.getName()).fine(() -> "Removed temporary file " + localFile.getName());
            } else {
                Logger.getLogger(S3GzipCallable.class.getName()).fine(() -> "Not removed temporary file " + localFile.getName() + " exists? " + localFile.exists());
            }
        }
    }

    @Override
    @SuppressFBWarnings({"RV_RETURN_VALUE_IGNORED_BAD_PRACTICE","OBL_UNSATISFIED_OBLIGATION"})
    public String invoke(FilePath file) throws IOException, InterruptedException {
        final File localFile = gzipFile(file);
        Upload upload = null;

        try {
            // This stream is asynchronously used in startUploading,
            // so we cannot use its AutoCloseable behaviour with a
            // try-with-resources statement, as that would likely
            // close the stream before the upload has succeeded.
            final InputStream gzippedStream = new FileInputStream(localFile);
            final Uploads.Metadata metadata = buildMetadata(file);
            long length = localFile.length();
            metadata.setContentLength(length);
            metadata.andThen(meta -> meta.contentEncoding("gzip")
            .contentLength(length));

            String md5 = MD5.generateFromFile(localFile);

            // Add the cleanup hook only after we have the MD5,
            // because the hook might delete the file immediately.
            upload = Uploads.getInstance().startUploading(getTransferManager(), file, gzippedStream, getDest().bucketName, getDest().objectName, metadata, new CleanupHook(localFile));

            return md5;
        } finally {
            // The upload might have finished before we installed the progress listener.
            if (upload == null || upload.completionFuture().isDone()) {
                // The progress listener might have fired before this,
                // but .delete() on non-existent path is ok, and the
                // temporary name won't be reused by anything
                localFile.delete();
            }
        }
    }
}
