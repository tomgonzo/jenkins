/*
 * The MIT License
 *
 * Copyright (c) 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.install;

import com.gargoylesoftware.htmlunit.Page;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.model.DownloadService;
import hudson.model.UpdateSite;
import hudson.security.AuthorizationStrategy;
import hudson.security.SecurityRealm;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;

import jenkins.model.Jenkins;
import jenkins.util.JSONSignatureValidator;
import org.apache.commons.io.FileUtils;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import org.apache.tools.ant.filters.StringInputStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.jvnet.hudson.test.JenkinsRule;
import static org.hamcrest.MatcherAssert.assertThat;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.SmokeTest;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Tests of {@link SetupWizard}.
 * @author Oleg Nenashev
 */
@Category(SmokeTest.class)
public class SetupWizardTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();
    
    @Rule
    public TemporaryFolder tmpdir = new TemporaryFolder();
    
    @Before 
    public void initSetupWizard() throws IOException, InterruptedException {
        final SetupWizard wizard = j.jenkins.getSetupWizard();
        wizard.init(true);
        
        // Retrieve admin credentials
        final FilePath adminPassFile = wizard.getInitialAdminPasswordFile();
        ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        adminPassFile.copyTo(ostream);
        final String password = ostream.toString();
    }
    
    @Test
    public void shouldReturnPluginListsByDefault() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        // TODO: This is a hack, wc.login does not work with the form
        j.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION);
        j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
        // wc.setCredentialsProvider(adminCredentialsProvider);
        // wc.login("admin");
        
        String response = jsonRequest(wc, "setupWizard/platformPluginList");
        assertThat("Missing plugin is suggestions ", response, containsString("active-directory"));
        assertThat("Missing category is suggestions ", response, containsString("Pipelines and Continuous Delivery"));
    }

    @Test
    @Issue("JENKINS-34833")
    public void shouldReturnUpdateSiteJSONIfSpecified() throws Exception {
        // Init the update site
        CustomLocalUpdateSite us = new CustomLocalUpdateSite(tmpdir.getRoot());
        us.init();
        j.jenkins.getUpdateCenter().getSites().add(us);

        // Prepare the connection
        JenkinsRule.WebClient wc = j.createWebClient();
        // TODO: This is a hack, wc.login does not work with the form
        j.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION);
        j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
        // wc.setCredentialsProvider(adminCredentialsProvider);
        // wc.login("admin");

        String response = jsonRequest(wc, "setupWizard/platformPluginList");
        assertThat("Missing plugin in suggestions ", response, containsString("antisamy-markup-formatter"));
        assertThat("Missing category in suggestions ", response, containsString("Organization and Administration"));
        assertThat("Unexpected plugin in suggestions ", response, not(containsString("active-directory")));
        assertThat("Unexpected category in suggestions ", response, not(containsString("Pipelines and Continuous Delivery")));
    }

    @Test
    @Issue("JENKINS-34833")
    public void shouldReturnWrappedUpdateSiteJSONIfSpecified() throws Exception {
        // Init the update site
        CustomLocalUpdateSiteWithWrapperJSON us = new CustomLocalUpdateSiteWithWrapperJSON(tmpdir.getRoot());
        us.init();
        j.jenkins.getUpdateCenter().getSites().add(us);

        // Prepare the connection
        JenkinsRule.WebClient wc = j.createWebClient();
        // TODO: This is a hack, wc.login does not work with the form
        j.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION);
        j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
        // wc.setCredentialsProvider(adminCredentialsProvider);
        // wc.login("admin");

        String response = jsonRequest(wc, "setupWizard/platformPluginList");
        assertThat("Missing plugin in suggestions ", response, containsString("dashboard-view"));
        assertThat("Missing category in suggestions ", response, containsString("Administration and Organization"));
        assertThat("Unexpected plugin in suggestions ", response, not(containsString("matrix-auth")));
        assertThat("Unexpected category in suggestions ", response, not(containsString("Pipelines and Continuous Delivery")));
    }

    @Test
    public void shouldProhibitAccessToPluginListWithoutAuth() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.assertFails("setupWizard/platformPluginList", 403);
        wc.assertFails("setupWizard/createAdminUser", 403);
        wc.assertFails("setupWizard/completeInstall", 403);
    }

    private String jsonRequest(JenkinsRule.WebClient wc, String path) throws Exception {
        // Try to call the actions method to retrieve the data
        final Page res;
        try {
            res = wc.goTo(path, null);
        } catch (Exception ex) {
            ex.getMessage();
            throw new AssertionError("Cannot get a response from " + path, ex);
        }
        final String responseJSON = res.getWebResponse().getContentAsString();
        return responseJSON;
    }

    private static final class CustomLocalUpdateSite extends UpdateSite {

        private final File tmpdir;

        CustomLocalUpdateSite(File tmpdir) throws MalformedURLException {
            super("custom-uc", tmpdir.toURI().toURL().toString() + "update-center.json");
            this.tmpdir = tmpdir;
        }

        public void init() throws IOException {
            File newFile = new File(tmpdir, "platform-plugins.json");
            FileUtils.write(newFile, "[ { "
                    + "\"category\":\"Organization and Administration\", "
                    + "\"plugins\": [ { \"name\": \"antisamy-markup-formatter\" } ]"
                    + "} ]");
        }
    }

    private static final class CustomLocalUpdateSiteWithWrapperJSON extends UpdateSite {

        private final File tmpdir;

        CustomLocalUpdateSiteWithWrapperJSON(File tmpdir) throws MalformedURLException {
            super("custom-uc2", tmpdir.toURI().toURL().toString() + "update-center.json");
            this.tmpdir = tmpdir;
        }

        public void init() throws IOException {
            File newFile = new File(tmpdir, "platform-plugins.json");
            FileUtils.write(newFile, "{ \"categories\" : [ { "
                    + "\"category\":\"Administration and Organization\", "
                    + "\"plugins\": [ { \"name\": \"dashboard-view\"} ]"
                    + "} ] }");
        }
    }

    @Test
    public void testRemoteUpdateSiteFailingValidation() throws Exception {
        URL baseUrl;
        final String serverContext = "/_relative/";
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new RemoteUpdateSiteHandler(serverContext, true));
        try {
            server.start();
            baseUrl = new URL("http", "localhost", connector.getLocalPort(), serverContext);

            // Init the update site
            CustomRemoteUpdateSite us = new CustomRemoteUpdateSite(baseUrl.toString(), false);
            j.jenkins.getUpdateCenter().getSites().add(us);

            // Prepare the connection
            JenkinsRule.WebClient wc = j.createWebClient();
            // TODO: This is a hack, wc.login does not work with the form
            j.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION);
            j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
            // wc.setCredentialsProvider(adminCredentialsProvider);
            // wc.login("admin");

            String response = jsonRequest(wc, "setupWizard/platformPluginList");
            // We need to assert that signature check fails, and we're falling back to the bundled resource
            assertThat("Missing plugin in suggestions ", response, not(containsString("my-plugin")));
            assertThat("Missing category in suggestions ", response, not(containsString("Very Useful Category")));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testRemoteUpdateSiteSkippingValidation() throws Exception {
        URL baseUrl;
        final String serverContext = "/_relative/";
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new RemoteUpdateSiteHandler(serverContext, true));
        try {
            server.start();
            DownloadService.signatureCheck = false;
            baseUrl = new URL("http", "localhost", connector.getLocalPort(), serverContext);

            // Init the update site
            CustomRemoteUpdateSite us = new CustomRemoteUpdateSite(baseUrl.toString(), false);
            j.jenkins.getUpdateCenter().getSites().add(us);


            // Prepare the connection
            JenkinsRule.WebClient wc = j.createWebClient();
            // TODO: This is a hack, wc.login does not work with the form
            j.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION);
            j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
            // wc.setCredentialsProvider(adminCredentialsProvider);
            // wc.login("admin");

            String response = jsonRequest(wc, "setupWizard/platformPluginList");
            // We need to assert that signature check fails, and we're falling back to the bundled resource
            assertThat("Missing plugin in suggestions ", response, containsString("my-plugin"));
            assertThat("Missing category in suggestions ", response, containsString("Very Useful Category"));
            assertThat("Unexpected plugin in suggestions ", response, not(containsString("matrix-auth")));
            assertThat("Unexpected category in suggestions ", response, not(containsString("Pipelines and Continuous Delivery")));
        } finally {
            DownloadService.signatureCheck = true;
            server.stop();
        }
    }

    @Test
    public void testRemoteUpdateSitePerformingValidation() throws Exception {
        URL baseUrl;
        final String serverContext = "/_relative/";
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new RemoteUpdateSiteHandler(serverContext, true));
        try {
            server.start();
            baseUrl = new URL("http", "localhost", connector.getLocalPort(), serverContext);

            // Init the update site
            CustomRemoteUpdateSite us = new CustomRemoteUpdateSite(baseUrl.toString(), true);
            j.jenkins.getUpdateCenter().getSites().add(us);


            // Prepare the connection
            JenkinsRule.WebClient wc = j.createWebClient();
            // TODO: This is a hack, wc.login does not work with the form
            j.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION);
            j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
            // wc.setCredentialsProvider(adminCredentialsProvider);
            // wc.login("admin");

            String response = jsonRequest(wc, "setupWizard/platformPluginList");
            // We need to assert that signature check fails, and we're falling back to the bundled resource
            assertThat("Missing plugin in suggestions ", response, containsString("my-plugin"));
            assertThat("Missing category in suggestions ", response, containsString("Very Useful Category"));
            assertThat("Unexpected plugin in suggestions ", response, not(containsString("matrix-auth")));
            assertThat("Unexpected category in suggestions ", response, not(containsString("Pipelines and Continuous Delivery")));
        } finally {
            server.stop();
        }
    }

    private static final class CustomRemoteUpdateSite extends UpdateSite {
        private boolean customValidator;

        CustomRemoteUpdateSite(String baseUrl, boolean customValidator) throws MalformedURLException {
            super("custom-uc2", baseUrl + "update-center.json");
            this.customValidator = customValidator;
        }

        @NonNull
        @Override
        protected JSONSignatureValidator getJsonSignatureValidator(String name) {
            if (customValidator) {
                return new CustomJSONSignatureValidator(CERT);
            }
            return super.getJsonSignatureValidator(name);
        }
    }

    private static class RemoteUpdateSiteHandler extends AbstractHandler {
        private String serverContext;
        private boolean includeSignature;

        // TODO we can always include the signature, a signature that isn't configured client-side behaves as if unsigned.
        RemoteUpdateSiteHandler(String serverContext, boolean includeSignature) {
            this.serverContext = serverContext;
            this.includeSignature = includeSignature;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            String responseBody = getWebServerResource(target, request.getParameter("version"));
            if (responseBody != null) {
                baseRequest.setHandled(true);
                response.setContentType("text/plain; charset=utf-8");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getOutputStream().write(responseBody.getBytes());
            } else {
                response.sendError(404);
            }
        }

        private String getWebServerResource(String target, String version) throws IOException {
            if (target.equals(serverContext + "platform-plugins.json")) {
                if (version == null) {
                    throw new IOException("?version parameter value is missing");
                }
                if (!version.equals(Jenkins.getVersion().toString())) {
                    throw new IOException("Unexpected ?version parameter value: " + version);
                }
                if (includeSignature) {
                    return "{ \"categories\":[ {\n" +
                            "\"category\":\"Very Useful Category\",\n" +
                            "\"plugins\":[ {\"name\":\"my-plugin\", \"suggested\":false } ]\n" +
                            "} ],\n" +
                            "\"signature\":{\n" +
                            "\"certificates\":[\n" +
                            "\"MIIFdDCCA1wCCQC9xxIN0UapszANBgkqhkiG9w0BAQsFADB8MRowGAYDVQQKDBFsb2NhbC1kZXZlbG9wbWVudDEaMBgGA1UECwwRbG9jYWwtZGV2ZWxvcG1lbnQxGjAYBgNVBAMMEWxvY2FsLWRldmVsb3BtZW50MSYwJAYJKoZIhvcNAQkBFhdleGFtcGxlQGV4YW1wbGUuaW52YWxpZDAeFw0yMTAzMTgxODM0MzFaFw0yNjAyMjAxODM0MzFaMHwxGjAYBgNVBAoMEWxvY2FsLWRldmVsb3BtZW50MRowGAYDVQQLDBFsb2NhbC1kZXZlbG9wbWVudDEaMBgGA1UEAwwRbG9jYWwtZGV2ZWxvcG1lbnQxJjAkBgkqhkiG9w0BCQEWF2V4YW1wbGVAZXhhbXBsZS5pbnZhbGlkMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAz0k6D4HtPoSvLUKrtcHkBHTyd4Zd1EZkwD7V3CgoLOFYboozjPX3U+q3paGUaQZ9Ejggbq5Cwsv7PHpn89OQ20Cy53RF19pChX2Zx/uuF5SjMapchtAJIwj0EjQNo5MqYuRjm6kOFA6ZwD13nLxeH1YfWeKN7xPkmbMkc1ruXrZNd9XPYtmGNFR8oH/N1CYc7dZ3RNZLwMNZv3981yVcZ19T5JvyxlTCaWDsr6ODgNx0zG0mc0nAdDi+TSNxzJfoIF+klkc9IODsqhrE6CpD0R1Wny7sedUc/cxviO2lmKGq+3bqUIq4Xlr/q8kCFVC478QM9zj6/SmFzMioXGq4JFHj0m1Am6pIpay1hqCZeKXXIRMs80KC3XCQ2+z+woP/Iu4fJyclwGxfPh0zq+cPDwtyH5VkX1QBMv3ge2Ks7wESTd3HaZrkt+/2Mk9eo7o0IVxeq/BQ9rwvtzfrxynuhLBXOTh1ViZYC58wYT8UZ/3F8GKveW3LlgXf0cdpTl7xGUdw4dOq5IkgPgJZZ6oB757NXPLa68wlcx8acRA7xv4IqdjuSDEZVF48UJi57GPJKnhi+9bWFpz7l1c0Yh2LGY3DoHPJ4WXctFrHTaY3+vAyiSBgFMCwYyxTdI33b5MeMlS56xuBUxZCqsnwlqvDH2jECa+oqzOa29s0EftdMn8CAwEAATANBgkqhkiG9w0BAQsFAAOCAgEAQ42dkh6fmFVoCRzh/UUC/XCyiXL3DvzzPmjuwKB2l3+C2ysvTtpCsiVj3KZcJztxbPMysllQ5M6VGbKuzwxtsBNn+XQwpbM9MBYJH7q9Xl1p+T3/KOHY3mbXh5+Ka1m7cJHkj6E1P6yIykDLC3pF4MEzqMW33NBxymeax3Xgztq+sPxfV0qv8102rezFOsO5ke1a52zlLgyuzTMPLgc1mBiQfM1q0b+aQl05dU54k1dEN8DVCPBZXbFc2s6ewXmPu+yyDqK/iMORa8jmHJtZpL+UMzPNrLxC7k32LQBVt/OZFiQDCW9oATI7wVKhC/yls/cP5mfrhckrP/uxqTKwOS6TgkwT/rHQ11TzBlLCKX1RqfGn20zQ/lMyvfK4uFBpiMZkg/m9Wr5DiOMLHj44thlI3oH9Qko3kBLj4nr01Vg29IJgsPbkNYKrOwGFXgCWqpNJZEVqjA5SNpRNFMUpRtDLrJla3xIWRxo/rCCe5GNBoJeT9d0TlKu//lCOQUwzcWE8K5yesjWXPXCXDRA0a+/LSi9YIqGUNWAvoPQ5FWyRekcAu5mKr6BqaertX2dzF3/PYJ2VgW9jT8nDi1D0zmEdbrtKVGuKqR04SI6ZI8NvyheUbzsV0q4Qt2V2uHcQ4j8AErff7WDPFdn7P7FyaV0h0zBv6/XJs1JSb3nwtHA=\"\n" +
                            "],\n" +
                            "\"correct_digest\":\"Gm5yn9FM+pTT5yHYCZZchmXjd4U=\",\n" +
                            "\"correct_digest512\":\"67a9853b8a3fe322a321af26915c7fd503d89be787b3f7c1e8d8a8413d7458ad75f12f75fe1b5d9c672e048e1bd3b60fc294779f8606701e0435b41dc5602e97\",\n" +
                            "\"correct_signature\":\"XjqrkmZ4FB8WDewkKymuoG13P477SlnugZ/rIea6/tP27urcwHhLq9HpvfqFHBN+xcMStXat+m2E0MZvxEg4Tdaiw2rELHvPCglm1pUPIBeXSNMppoyTy+qg9w2Z3nNGB8ZoCkJy2/Bq28P/CzKwgBuBeKObAk6t6Xuhwm+MbxDHAg1j/Hman9pj9X9AeOW6mlGXG7wVgtS3CwGf7dtNQNnNjIWosvMe4WLw9Yo2JuVBi+p3VtxMfwafMbZEwGK9Dh1EFZHiBsjb+ksvSFfEa/h3C2EXQYi8+jIbGYO0HCVZPvFv3TRvj30ogku2yXvUrK0uAw5e65e2dZmRMr5yCrOfzRYAafxd47peSn2+kyWquxsTzUqwhelquvY3w9fE3Hmv4tSIU9lJvBUeu/IQEDJ3ET5XzFX3fHhw5O3FV54eLWLsTx4tfRWVfn90Nu/YEpz5F67CZE75ci9wGzOTcqVkC9aW2jqAS8TlpgkDgaflggG2mjJIjOHuyrUBMD9X0Ie7UUY/6H/j4fnTkHy+ea80VKhrn9S+qggIjbvp9VH+xgl0vHQ5I3+NwOchVOdIsCU6dZkZeOkohfcc4LLSihJ86zovi9PJmVRv2CATGwghika6hhfAhKjh7ZbD0Dcd3qO3qbqud/LEN5l7fJBaO4iMMKWzlV0Sa7k1q/zWxp8=\",\n" +
                            "\"correct_signature512\":\"a197809570f986fa34f3e264b11a3beed3e08794b0f8991302a1418544ef7d75beb296c5fa0e17b8eeb06305e5279ea8680ee2f161b2ec9c926b2491aa1286ecd82865e9141f790114762fa09e1f23f4f521f2838753082226a6cb28f1439312ec1eec66aea7f220035b2808bd3f30300f81a6685e8f89b82a20f470706bc83c2ffb2e5d65c0a682263d291849dddafe0be442d9b73e3737a86b5992d96698272b9d9efaa8c2475a4020e5cd8d56b715fb6844d98539ab4c31eb7a8080b8231ee2452fc765407203f858af5211a3288ee8f2f9cefa4dd02f5164a1b241681cf7c81b203ded13e47484a041dc10eb988c398a0a94bed8ddd70a0c65a6a378f09e5e138a802300731865fc9e894c7eeeaf59efbe8f8f845ae101cbcd32ebba017d4413c806cbca1a0ef0e586fe1f43b9d015574ef8d2da0808df574fe6946c6301d82b2267f9751e977888568946870b17c001f3a09203f71f79035b55b7d77b2fd2ef00db89a0839cd21ee5bd2bc1b552c67d48f8d0c76888b8b64d1007a6594d0975b6b3220d180daadb075607a406b5e5ecd4f44c79536017bb37847d6e5bbd309579e88527e7dddb459c8d722157ea22dbb2686a2ef4e3d9ca27b59144326ea1f6eab27b51dadb7355414c41f9d0c9185a63ab3b0a4da40a37cb2680d0999d46cddddfdad2d10fe11d1104486db5923a95c2b1ad98f26882ee91c3c5f347b6\"\n" +
                            "} }";
                } else {
                    return "{ \"categories\" : [ { "
                            + "\"category\":\"Very Useful Category\", "
                            + "\"plugins\": [ { \"name\": \"my-plugin\"} ]"
                            + "} ] }";
                }
            }
            return null;
        }
    }

    private static class CustomJSONSignatureValidator extends JSONSignatureValidator {
        private String cert;

        public CustomJSONSignatureValidator(String cert) {
            super("Custom JSON signature validator");
            this.cert = cert;
        }

        @Override
        protected Set<TrustAnchor> loadTrustAnchors(CertificateFactory cf) throws IOException {
            Set<TrustAnchor> trustAnchors = new HashSet<>();
            try {
                Certificate certificate = cf.generateCertificate(new StringInputStream(cert));
                trustAnchors.add(new TrustAnchor((X509Certificate) certificate, null));
            } catch (CertificateException ex) {
                throw new IOException(ex);
            }
            return trustAnchors;
        }
    }

    // Used to test signature validation in remote platform-plugins JSON
    // Generated using:
    // openssl genrsa -out demo.key 4096
    // openssl req -new -x509 -days 1800 -key demo.key -out demo.crt -subj "/C=/ST=/L=/O=local-development/OU=local-development/CN=local-development/emailAddress=example@example.invalid"
    // Then signed using update-center2 args: --key demo.key --certificate demo.crt --pretty-json --root-certificate demo.crt --generate-platform-plugins --skip-update-center --www-dir output
    private static final String CERT = "-----BEGIN CERTIFICATE-----\n" +
            "MIIFdDCCA1wCCQC9xxIN0UapszANBgkqhkiG9w0BAQsFADB8MRowGAYDVQQKDBFs\n" +
            "b2NhbC1kZXZlbG9wbWVudDEaMBgGA1UECwwRbG9jYWwtZGV2ZWxvcG1lbnQxGjAY\n" +
            "BgNVBAMMEWxvY2FsLWRldmVsb3BtZW50MSYwJAYJKoZIhvcNAQkBFhdleGFtcGxl\n" +
            "QGV4YW1wbGUuaW52YWxpZDAeFw0yMTAzMTgxODM0MzFaFw0yNjAyMjAxODM0MzFa\n" +
            "MHwxGjAYBgNVBAoMEWxvY2FsLWRldmVsb3BtZW50MRowGAYDVQQLDBFsb2NhbC1k\n" +
            "ZXZlbG9wbWVudDEaMBgGA1UEAwwRbG9jYWwtZGV2ZWxvcG1lbnQxJjAkBgkqhkiG\n" +
            "9w0BCQEWF2V4YW1wbGVAZXhhbXBsZS5pbnZhbGlkMIICIjANBgkqhkiG9w0BAQEF\n" +
            "AAOCAg8AMIICCgKCAgEAz0k6D4HtPoSvLUKrtcHkBHTyd4Zd1EZkwD7V3CgoLOFY\n" +
            "boozjPX3U+q3paGUaQZ9Ejggbq5Cwsv7PHpn89OQ20Cy53RF19pChX2Zx/uuF5Sj\n" +
            "MapchtAJIwj0EjQNo5MqYuRjm6kOFA6ZwD13nLxeH1YfWeKN7xPkmbMkc1ruXrZN\n" +
            "d9XPYtmGNFR8oH/N1CYc7dZ3RNZLwMNZv3981yVcZ19T5JvyxlTCaWDsr6ODgNx0\n" +
            "zG0mc0nAdDi+TSNxzJfoIF+klkc9IODsqhrE6CpD0R1Wny7sedUc/cxviO2lmKGq\n" +
            "+3bqUIq4Xlr/q8kCFVC478QM9zj6/SmFzMioXGq4JFHj0m1Am6pIpay1hqCZeKXX\n" +
            "IRMs80KC3XCQ2+z+woP/Iu4fJyclwGxfPh0zq+cPDwtyH5VkX1QBMv3ge2Ks7wES\n" +
            "Td3HaZrkt+/2Mk9eo7o0IVxeq/BQ9rwvtzfrxynuhLBXOTh1ViZYC58wYT8UZ/3F\n" +
            "8GKveW3LlgXf0cdpTl7xGUdw4dOq5IkgPgJZZ6oB757NXPLa68wlcx8acRA7xv4I\n" +
            "qdjuSDEZVF48UJi57GPJKnhi+9bWFpz7l1c0Yh2LGY3DoHPJ4WXctFrHTaY3+vAy\n" +
            "iSBgFMCwYyxTdI33b5MeMlS56xuBUxZCqsnwlqvDH2jECa+oqzOa29s0EftdMn8C\n" +
            "AwEAATANBgkqhkiG9w0BAQsFAAOCAgEAQ42dkh6fmFVoCRzh/UUC/XCyiXL3Dvzz\n" +
            "PmjuwKB2l3+C2ysvTtpCsiVj3KZcJztxbPMysllQ5M6VGbKuzwxtsBNn+XQwpbM9\n" +
            "MBYJH7q9Xl1p+T3/KOHY3mbXh5+Ka1m7cJHkj6E1P6yIykDLC3pF4MEzqMW33NBx\n" +
            "ymeax3Xgztq+sPxfV0qv8102rezFOsO5ke1a52zlLgyuzTMPLgc1mBiQfM1q0b+a\n" +
            "Ql05dU54k1dEN8DVCPBZXbFc2s6ewXmPu+yyDqK/iMORa8jmHJtZpL+UMzPNrLxC\n" +
            "7k32LQBVt/OZFiQDCW9oATI7wVKhC/yls/cP5mfrhckrP/uxqTKwOS6TgkwT/rHQ\n" +
            "11TzBlLCKX1RqfGn20zQ/lMyvfK4uFBpiMZkg/m9Wr5DiOMLHj44thlI3oH9Qko3\n" +
            "kBLj4nr01Vg29IJgsPbkNYKrOwGFXgCWqpNJZEVqjA5SNpRNFMUpRtDLrJla3xIW\n" +
            "Rxo/rCCe5GNBoJeT9d0TlKu//lCOQUwzcWE8K5yesjWXPXCXDRA0a+/LSi9YIqGU\n" +
            "NWAvoPQ5FWyRekcAu5mKr6BqaertX2dzF3/PYJ2VgW9jT8nDi1D0zmEdbrtKVGuK\n" +
            "qR04SI6ZI8NvyheUbzsV0q4Qt2V2uHcQ4j8AErff7WDPFdn7P7FyaV0h0zBv6/XJ\n" +
            "s1JSb3nwtHA=\n" +
            "-----END CERTIFICATE-----\n";

}
