package ch.digitalfondue.jscoverproxy;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

import jscover.Main;
import jscover.server.ConfigurationForServer;

@Mojo(name = "coverage", defaultPhase = LifecyclePhase.VERIFY)
public class JSCoverProxyMavenMojo extends AbstractMojo {

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
	private String jsSrcDir;

	@Parameter
	private boolean generateXMLSUMMARY;

	@Parameter
	private boolean generateLCOV;

	@Parameter
	private boolean generateCOBERTURAXML;

	@Parameter
	private List<String> noInstruments;

	@Parameter(defaultValue = "60")
	private int timeout;

	@Parameter(property = "skipTests")
	protected boolean skipTests;

	@Parameter(property = "maven.test.skip")
	protected boolean mvnTestSkip;

	private HtmlUnitDriver getWebClient(int portForJSCoverProxy) {
		Proxy proxy = new Proxy().setHttpProxy("localhost:" + portForJSCoverProxy);
		DesiredCapabilities caps = new DesiredCapabilities();
		caps.setCapability(CapabilityType.PROXY, proxy);
		caps.setJavascriptEnabled(true);
		caps.setBrowserName(BrowserType.HTMLUNIT);
		return new HtmlUnitDriver(caps);
	}

	public void execute() throws MojoExecutionException, MojoFailureException {

		if(skipTests || mvnTestSkip) {
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

		final List<String> args = new ArrayList<>(Arrays.asList("-ws", "--port=" + portForJSCoverProxy, "--proxy",
				"--local-storage", "--report-dir=" + outputDir.getAbsolutePath()));
		if (noInstruments != null && !noInstruments.isEmpty()) {
			args.addAll(noInstruments.stream().map(s -> "--no-instrument=" + s).collect(Collectors.toList()));
		}

		getLog().info("Output dir for report is " + outputDir.getAbsolutePath());
		getLog().info("Url for tests is " + baseUrl);

		final Main main = new Main();
		main.initialize();

		Thread server = new Thread(() -> main.runServer(ConfigurationForServer.parse(args.toArray(new String[] {}))));

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
			List<String> params = new ArrayList<>(Arrays.asList("--format="+type, reportPath));
			if(jsSrcDirCheck) {
				params.add(jsSrcDir);
			}
			jscover.report.Main.main(params.toArray(new String[] {}));
		} catch (IOException e) {
			throw new MojoExecutionException("Error while generating " + type, e);
		}
		getLog().info(type + " generated");
	}

}
