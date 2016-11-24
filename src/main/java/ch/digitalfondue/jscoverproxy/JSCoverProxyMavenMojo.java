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
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
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

	private HtmlUnitDriver getWebClient(int portForJSCoverProxy) {
		Proxy proxy = new Proxy().setHttpProxy("localhost:" + portForJSCoverProxy);
		DesiredCapabilities caps = new DesiredCapabilities();
		caps.setCapability(CapabilityType.PROXY, proxy);
		caps.setJavascriptEnabled(true);
		return new HtmlUnitDriver(caps);
	}

	public void execute() throws MojoExecutionException, MojoFailureException {

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
		new WebDriverWait(webClient, 20).until(ExpectedConditions
				.textToBePresentInElementLocated(By.cssSelector(cssSelectorForEndTest), textForEndTest));

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
				getLog().info("Generating XMLSUMMARY");
				try {
					jscover.report.Main.main(new String[] { "--format=XMLSUMMARY", reportPath });
				} catch (IOException e) {
					throw new MojoExecutionException("Error while generating XMLSUMMARY", e);
				}
				getLog().info("XMLSUMMARY generated");
			}

			if (generateLCOV) {
				getLog().info("Generating LCOV");

				if (jsSrcDir == null) {
					getLog().error("jsSrcDir is mandatory when generating LCOV report");
					throw new MojoExecutionException("jsSrcDir is mandatory when generating LCOV report");
				}

				try {
					jscover.report.Main.main(new String[] { "--format=LCOV", reportPath, jsSrcDir });
				} catch (IOException e) {
					throw new MojoExecutionException("Error while generating LCOV", e);
				}
				getLog().info("LCOV generated");
			}

			if (generateCOBERTURAXML) {
				getLog().info("Generating COBERTURAXML");

				if (jsSrcDir == null) {
					getLog().error("jsSrcDir is mandatory when generating COBERTURAXML report");
					throw new MojoExecutionException("jsSrcDir is mandatory when generating COBERTURAXML report");
				}

				try {
					jscover.report.Main.main(new String[] { "--format=COBERTURAXML", reportPath, jsSrcDir });
				} catch (IOException e) {
					throw new MojoExecutionException("Error while generating COBERTURAXML", e);
				}
				getLog().info("COBERTURAXML generated");
			}

			getLog().info("Finished JSCoverProxy");
		}

	}

}
