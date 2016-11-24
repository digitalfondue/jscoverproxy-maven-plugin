package ch.digitalfondue.jscoverproxy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

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
	private String host;

	@Parameter(required = true)
	private int port;

	@Parameter(defaultValue = "coverage")
	private String reportName;

	private static String reportDir = "target/reports/jscover-localstorage-general";

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

		final String[] args = new String[] { "-ws", "--port=" + portForJSCoverProxy, "--proxy", "--local-storage",
				"--no-instrument=webjars/", "--no-instrument=classpath/", "--no-instrument=spec/",
				"--report-dir=" + reportDir };

		final Main main = new Main();
		main.initialize();

		Thread server = new Thread(new Runnable() {
			public void run() {
				main.runServer(ConfigurationForServer.parse(args));
			}
		});

		server.start();

		WebDriver webClient = getWebClient(portForJSCoverProxy);
		webClient.get("http://" + host + ":" + port);
		new WebDriverWait(webClient, 20).until(
				ExpectedConditions.textToBePresentInElementLocated(By.className("jasmine-duration"), "finished"));

		// execute the creation of the report on our side, htmlunit crash when
		// doing the http call...
		String res = (String) ((JavascriptExecutor) webClient).executeAsyncScript(
				"var callback = arguments[arguments.length - 1];" + "callback(jscoverage_serializeCoverageToJSON());");

		HttpClient httpClient = HttpClientBuilder.create().build();
		HttpPost request = new HttpPost("http://localhost:" + portForJSCoverProxy + "/jscoverage-store/" + reportName);
		request.addHeader("content-type", "application/json");
		request.setEntity(new StringEntity(res, StandardCharsets.UTF_8));
		try {
			httpClient.execute(request);
		} catch (IOException e) {
			throw new MojoExecutionException("failed to store the coverage", e);
		} finally {
			webClient.close();
			webClient.quit();
			main.stop();
		}

	}

}
