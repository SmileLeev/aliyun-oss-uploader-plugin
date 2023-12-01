package org.inurl.jenkins.plugin;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.arronlong.httpclientutil.HttpClientUtil;
import com.arronlong.httpclientutil.common.HttpConfig;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class OSSPublisher extends Publisher implements SimpleBuildStep {

    private final String endpoint;

    private final String accessKeyId;

    private final Secret accessKeySecret;

    private final String bucketName;

    private final String localPath;

    private final String remotePath;

    private final String maxRetries;

    private final boolean autoSign;

    private final String qyWechat;

    private final Map<FilePath, String> signMaps = new HashMap<FilePath, String>();

    public String getEndpoint() {
        return endpoint;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret.getPlainText();
    }

    public String getBucketName() {
        return bucketName;
    }

    public String getLocalPath() {
        return localPath;
    }

    public String getRemotePath() {
        return remotePath;
    }

    public int getMaxRetries() {
        return StringUtils.isEmpty(maxRetries) ? 3 : Integer.parseInt(maxRetries);
    }

    public boolean getAutoSign() {
        return autoSign;
    }

    public String getQyWechat() {
        return qyWechat;
    }

    @DataBoundConstructor
    public OSSPublisher(String endpoint, String accessKeyId, String accessKeySecret, String bucketName,
                        String localPath, String remotePath, String maxRetries, boolean autoSign, String qyWechat) {
        this.endpoint = endpoint;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = Secret.fromString(accessKeySecret);
        this.bucketName = bucketName;
        this.localPath = localPath;
        this.remotePath = remotePath;
        this.maxRetries = maxRetries;
        this.autoSign = autoSign;
        this.qyWechat = qyWechat;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher,
                        @Nonnull TaskListener listener) throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();
        EnvVars envVars = run.getEnvironment(listener);
        OSSClient client = new OSSClient(endpoint, accessKeyId, accessKeySecret.getPlainText());
        logger.println("auto sign =>" + autoSign);
        logger.println("qy wechat url =>" + qyWechat);
        String local = localPath.substring(1);
        Result result = run.getResult();
        //运行不成功
        if (result == null || !result.equals(Result.SUCCESS)) {
            return;
        }

        String[] remotes = remotePath.split(",");
        for (String remote : remotes) {
            remote = remote.substring(1);
            String expandLocal = envVars.expand(local);
            String expandRemote = envVars.expand(remote);
            logger.println("expandLocalPath =>" + expandLocal);
            logger.println("expandRemotePath =>" + expandRemote);
            FilePath p = new FilePath(workspace, expandLocal);
            if (p.isDirectory()) {
                logger.println("upload dir => " + p);
                upload(client, logger, expandRemote, p, true);
                logger.println("upload dir success");
            } else {
                logger.println("upload file => " + p);
                uploadFile(client, logger, expandRemote, p);
                logger.println("upload file success");
            }
            if (qyWechat != null && !qyWechat.isEmpty()) {
                sendQWNotify(logger);
            }
        }

    }

    private void sendQWNotify(PrintStream logger) {
        if (signMaps.isEmpty()) {
            logger.println("==========> skip send qy wechat by emtpy");
            return;
        }
        try {
            //组装内容
            StringBuilder content = new StringBuilder();
            content.append("<font color=\"info\">【OSS Download Url】</font>\n");
            for (FilePath file : signMaps.keySet()) {
                content.append("> ").append(file.getName()).append(" : [ 点击下载 ](").append(signMaps.get(file)).append(")\n");
//                content.append(" >" + file.getName() + "：<font color=\"comment\">" + signMaps.get(file) + "</font>\n");
            }

            Map markdown = new HashMap<String, Object>();
            markdown.put("content", content.toString());

            Map data = new HashMap<String, Object>();
            data.put("msgtype", "markdown");
            data.put("markdown", markdown);

            String req = JSONObject.fromObject(data).toString();
            push(qyWechat, req);
        } catch (Exception e) {
            logger.println("==========> notify failed <============");
            logger.println("Error Message:" + e.getMessage());
        }
    }

    private void upload(OSSClient client, PrintStream logger, String base, FilePath path, boolean root)
            throws InterruptedException, IOException {
        if (path.isDirectory()) {
            for (FilePath f : path.list()) {
                upload(client, logger, base + (root ? "" : ("/" + path.getName())), f, false);
            }
            return;
        }
        uploadFile(client, logger, base + "/" + path.getName(), path);
    }

    private void uploadFile(OSSClient client, PrintStream logger, String key, FilePath path)
            throws InterruptedException, IOException {
        if (!path.exists()) {
            logger.println("file [" + path.getRemote() + "] not exists, skipped");
            return;
        }
        int maxRetries = getMaxRetries();
        int retries = 0;
        do {
            if (retries > 0) {
                logger.println("upload retrying (" + retries + "/" + maxRetries + ")");
            }
            try {
                uploadFile0(client, logger, key, path);
                return;
            } catch (Exception e) {
                e.printStackTrace(logger);
            }
        } while ((++retries) <= maxRetries);
        throw new RuntimeException("upload fail, more than the max of retries");
    }

    private void uploadFile0(OSSClient client, PrintStream logger, String key, FilePath path)
            throws InterruptedException, IOException {
        String realKey = key;
        if (realKey.startsWith("/")) {
            realKey = realKey.substring(1);
        }

        InputStream inputStream = path.read();
        logger.println("uploading [" + path.getRemote() + "] to [" + realKey + "]");
        client.putObject(bucketName, realKey, inputStream);
        if (autoSign) {
            signFile(client, logger, bucketName, realKey, path);
        }
    }

    private void signFile(OSSClient ossClient, PrintStream logger, String bucketName, String realKey, FilePath path) {
        URL signedUrl = null;
        try {
            // 指定生成的签名URL过期时间，单位为毫秒。本示例以设置过期时间为1小时为例。
            Date expiration = new Date(new Date().getTime() + 30 * 24 * 3600 * 1000L);

            // 生成签名URL。
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, realKey, HttpMethod.GET);
            // 设置过期时间。
            request.setExpiration(expiration);

            // 通过HTTP GET请求生成签名URL。
            signedUrl = ossClient.generatePresignedUrl(request);
            // 打印签名URL。
            logger.println("signed url for getObject: " + signedUrl);
            signMaps.put(path, signedUrl.toString());
        } catch (OSSException oe) {
            logger.println("Caught an OSSException, which means your request made it to OSS, "
                    + "but was rejected with an error response for some reason.");
            logger.println("Error Message:" + oe.getErrorMessage());
            logger.println("Error Code:" + oe.getErrorCode());
            logger.println("Request ID:" + oe.getRequestId());
            logger.println("Host ID:" + oe.getHostId());
        } catch (ClientException ce) {
            logger.println("Caught an ClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with OSS, "
                    + "such as not being able to access the network.");
            logger.println("Error Message:" + ce.getMessage());
        }
    }

    public static String push(String url, String data) throws Exception {
        HttpConfig httpConfig;
        HttpClient httpClient;
        HttpClientBuilder httpClientBuilder = HttpClients.custom();
        if (url.startsWith("https")) {
            SSLContext sslContext = SSLContexts.custom().build();
            SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(
                    sslContext,
                    new String[]{"TLSv1", "TLSv1.1", "TLSv1.2"},
                    null,
                    NoopHostnameVerifier.INSTANCE
            );
            httpClientBuilder.setSSLSocketFactory(sslConnectionSocketFactory);
        }

        httpClient = httpClientBuilder.build();
        //普通请求
        httpConfig = HttpConfig.custom().client(httpClient).url(url).json(data).encoding("utf-8");
        String result = HttpClientUtil.post(httpConfig);
        return result;
    }

    @Symbol("aliyunOSSUpload")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public FormValidation doCheckMaxRetries(@QueryParameter String value) {
            try {
                Integer.parseInt(value);
            } catch (Exception e) {
                return FormValidation.error(Messages.OSSPublish_MaxRetiesMustBeNumbers());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckEndpoint(@QueryParameter(required = true) String value) {
            return checkValue(value, Messages.OSSPublish_MissingEndpoint());
        }

        public FormValidation doCheckAccessKeyId(@QueryParameter(required = true) String value) {
            return checkValue(value, Messages.OSSPublish_MissingAccessKeyId());
        }

        public FormValidation doCheckAccessKeySecret(@QueryParameter(required = true) String value) {
            return checkValue(value, Messages.OSSPublish_MissingAccessKeySecret());
        }

        public FormValidation doCheckBucketName(@QueryParameter(required = true) String value) {
            return checkValue(value, Messages.OSSPublish_MissingBucketName());
        }

        public FormValidation doCheckLocalPath(@QueryParameter(required = true) String value) {
            return checkBeginWithSlash(value);
        }

        public FormValidation doCheckRemotePath(@QueryParameter(required = true) String value) {
            return checkBeginWithSlash(value);
        }

        private FormValidation checkBeginWithSlash(String value) {
            if (!value.startsWith("/")) {
                return FormValidation.error(Messages.OSSPublish_MustBeginWithSlash());
            }
            return FormValidation.ok();
        }

        private FormValidation checkValue(String value, String message) {
            if (StringUtils.isEmpty(value)) {
                return FormValidation.error(message);
            }
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.OSSPublish_DisplayName();
        }
    }

}
