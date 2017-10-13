package ch.digitalfondue.jscoverproxy;

import com.gargoylesoftware.htmlunit.WebClient;
import com.google.javascript.jscomp.parsing.Config;
import jscover.ConfigurationCommon;
import jscover.Main;
import jscover.server.ConfigurationForServer;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import org.openqa.selenium.remote.BrowserType;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.String.format;
import static jscover.ConfigurationCommon.*;

@Mojo(name = "coverage", defaultPhase = LifecyclePhase.VERIFY)
public class JSCoverProxyMavenMojo extends AbstractMojo {

    private ConfigurationForServer defaults = new ConfigurationForServer();

    @Parameter(required = true)
    private String baseUrl;

    @Parameter(defaultValue = "coverage")
    private String reportName;

    @Parameter(required = true)
    private File outputDir;

    @Parameter
    private String cssSelectorForEndTest = ".jasmine-duration";

    @Parameter
    private String textForEndTest = "finished";

    @Parameter
    private File jsSrcDir;

    @Parameter
    private boolean generateXMLSUMMARY;

    @Parameter
    private boolean generateLCOV;

    @Parameter
    private boolean generateCOBERTURAXML;

    @Parameter
    private List<String> removeJsSnippets = new ArrayList<>();

    @Parameter
    private List<String> noInstruments;

    @Parameter
    private Config.LanguageMode ecmaVersion = defaults.getECMAVersion();

    @Parameter
    protected final List<String> instrumentPathArgs = new ArrayList<>();

    @Parameter
    private boolean includeUnloadedJS = defaults.isIncludeUnloadedJS();

    @Parameter
    protected boolean detectCoalesce = defaults.isDetectCoalesce();

    @Parameter(defaultValue = "60")
    private int timeout;

    @Parameter(property = "skipTests")
    protected boolean skipTests;

    @Parameter(property = "maven.test.skip")
    protected boolean mvnTestSkip;



    private WebDriver getWebClient(int portForJSCoverProxy) {
        Proxy proxy = new Proxy().setHttpProxy("localhost:" + portForJSCoverProxy);
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setCapability(CapabilityType.PROXY, proxy);
        caps.setJavascriptEnabled(true);
        caps.setBrowserName(BrowserType.HTMLUNIT);
        return new HtmlUnitDriver(caps) {
            @Override
            protected WebClient modifyWebClient(WebClient client) {
                client.setScriptPreProcessor((htmlPage, sourceCode, sourceName, lineNumber, htmlElement) -> {
                    if(removeJsSnippets != null && !removeJsSnippets.isEmpty()) {
                        for(String toReplace : removeJsSnippets) {
                            sourceCode = sourceCode.replace(toReplace, "");
                        }
                        return sourceCode;
                    } else {
                        return sourceCode;
                    }

                });
                return client;
            }
        };
    }

    public void execute() throws MojoExecutionException, MojoFailureException {

        if (skipTests || mvnTestSkip) {
            getLog().info("Skipping jscover-proxy-maven");
            return;
        }

        int portForJSCoverProxy = 3129;

        // find next free port (beware, race condition could be possible)
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            portForJSCoverProxy = s.getLocalPort();
        } catch (IOException e) {
        }

        ConfigurationForServer config = getConfigurationForServer(portForJSCoverProxy);
        config.validate();
        if (config.isInvalid()) {
            throw new MojoExecutionException("Invalid configuration");
        }

        getLog().info("Output dir for report is " + outputDir.getAbsolutePath());
        getLog().info("Url for tests is " + baseUrl);

        final Main main = new Main();
        main.initialize();

        Thread server = new Thread(() -> main.runServer(config));

        getLog().info("Started JSCover proxy server");
        server.start();

        WebDriver webClient = getWebClient(portForJSCoverProxy);

        getLog().info("WebDriver is going to url: " + baseUrl);

        webClient.get(baseUrl);

        ExpectedCondition<Boolean> condition = ExpectedConditions.textToBePresentInElementLocated(By.cssSelector(cssSelectorForEndTest), textForEndTest);

        new WebDriverWait(webClient, timeout).until((webDriver) -> condition.apply(webDriver));

        getLog().info("Test ended, generating report");

        // execute the creation of the report on our side, htmlunit crash when
        // doing the http call...
        String res = (String) ((JavascriptExecutor) webClient).executeAsyncScript(
                "var callback = arguments[arguments.length - 1];" + "callback(jscoverage_serializeCoverageToJSON());");

        getLog().info("Coverage extracted, submitting to JSCover");

        HttpClient httpClient = HttpClientBuilder.create().build();
        String reportNameEncoded = new String(URLCodec.encodeUrl(null, reportName.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        HttpPost request = new HttpPost(
                "http://localhost:" + portForJSCoverProxy + "/jscoverage-store/" + reportNameEncoded);
        request.addHeader("content-type", "application/json");
        request.setEntity(new StringEntity(res, StandardCharsets.UTF_8));
        try {
            httpClient.execute(request);
            getLog().info("Coverage Submitted to JSCover");
        } catch (IOException e) {
            throw new MojoExecutionException("failed to store the coverage", e);
        } finally {
            webClient.close();
            webClient.quit();
            main.stop();

            String reportPath = new File(outputDir, reportName).getAbsolutePath();
            if (generateXMLSUMMARY) {
                generate("XMLSUMMARY", reportPath, false);
            }

            if (generateLCOV) {
                generate("LCOV", reportPath, true);
            }

            if (generateCOBERTURAXML) {
                generate("COBERTURAXML", reportPath, true);
            }

            getLog().info("Finished JSCoverProxy");
        }
    }


    private void generate(String type, String reportPath, boolean jsSrcDirCheck) throws MojoExecutionException {
        getLog().info("Generating " + type);

        if (jsSrcDirCheck && jsSrcDir == null) {
            getLog().error("jsSrcDir is mandatory when generating " + type + " report");
            throw new MojoExecutionException("jsSrcDir is mandatory when generating " + type + " report");
        }

        try {
            List<String> params = new ArrayList<>(Arrays.asList("--format=" + type, reportPath));
            if (jsSrcDirCheck) {
                params.add(jsSrcDir.getCanonicalPath());
            }
            jscover.report.Main.main(params.toArray(new String[]{}));
        } catch (IOException e) {
            throw new MojoExecutionException("Error while generating " + type, e);
        }
        getLog().info(type + " generated");
    }

    protected void setCommonConfiguration(ConfigurationCommon config) throws MojoExecutionException {
        config.setIncludeBranch(true);
        config.setIncludeFunction(true);
        config.setLocalStorage(true);
        config.setIncludeUnloadedJS(includeUnloadedJS);
        config.setECMAVersion(ecmaVersion);
        config.setDetectCoalesce(detectCoalesce);
        for (String instrumentArg : instrumentPathArgs) {
            if (instrumentArg.startsWith(NO_INSTRUMENT_PREFIX)) {
                config.addNoInstrument(instrumentArg);
            } else if (instrumentArg.startsWith(NO_INSTRUMENT_REG_PREFIX)) {
                config.addNoInstrumentReg(instrumentArg);
            } else if (instrumentArg.startsWith(ONLY_INSTRUMENT_REG_PREFIX)) {
                config.addOnlyInstrumentReg(instrumentArg);
            } else {
                throw new MojoExecutionException(format("Invalid instrument path option '%s'", instrumentArg));
            }
        }

        for (String noInstrument : noInstruments) {
            config.addNoInstrument("--no-instrument=" + noInstrument);
        }
    }


    private ConfigurationForServer getConfigurationForServer(int port) throws MojoExecutionException {
        ConfigurationForServer config = new ConfigurationForServer();
        //Common parameters
        setCommonConfiguration(config);
        //Server parameters
        if (jsSrcDir != null) {
            config.setDocumentRoot(jsSrcDir);
        }
        config.setPort(port);
        config.setProxy(true);
        config.setReportDir(outputDir.getAbsoluteFile());
        return config;
    }

}
