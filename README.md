# jscoverproxy-maven-plugin


[![Maven Central](https://img.shields.io/maven-central/v/ch.digitalfondue.jscover/jscoverproxy-maven-plugin.svg)](http://search.maven.org/#search%7Cga%7C1%7Ca%3A%22jscoverproxy-maven-plugin%22)


A maven plugin for replacing saga-maven-plugin, as it does not generate the correct coverage anymore when used with the jasmine-maven-plugin.

It's powered by [JSCover](https://tntim96.github.io/JSCover/) project.


# Example of use:

See: http://searls.github.io/jasmine-maven-plugin/code-coverage.html : replace the plugin block for saga-maven-plugin with:


```xml
<plugin>
	<groupId>ch.digitalfondue.jscover</groupId>
	<artifactId>jscoverproxy-maven-plugin</artifactId>
	<version>2.0.2.1</version>
	<executions>
		<execution>
			<goals>
				<goal>coverage</goal>
			</goals>
		</execution>
	</executions>
	<configuration>
		<baseUrl>http://localhost:${jasmine.serverPort}</baseUrl>
		<outputDir>${project.build.directory}/report/</outputDir>
		<noInstruments>
			<noInstrument>webjars/</noInstrument>
			<noInstrument>classpath/</noInstrument>
			<noInstrument>spec/</noInstrument>
		</noInstruments>
		<jsSrcDir>src/main/webapp/app</jsSrcDir><!-- mandatory for LCOV and  COBERTURAXML -->
		<generateXMLSUMMARY>true</generateXMLSUMMARY><!-- optional -->
		<generateLCOV>true</generateLCOV><!-- optional -->
		<generateCOBERTURAXML>true</generateCOBERTURAXML><!-- optional -->
		<timeout>60</timeout> <!-- default 60 seconds -->
		<!-- optionals:
		<includeUnloadedJS>true</includeUnloadedJS>
		<detectCoalesce>true</detectCoalesce>
		<removeJsSnippets>
		    <removeJsSnippet>alert('tests');</removeJsSnippet> 
		</removeJsSnippets>
		--><!-- you can remove js code if it cause issues -->
	</configuration>
</plugin>
```

for additional instrumentation configuration, use instead of "&lt;noInstruments>":

```
<instrumentPathArgs>
    <arg>--no-instrument=webjars/</arg>
    <arg>--no-instrument=classpath/</arg>
    <arg>--no-instrument=spec/</arg>
</instrumentPathArgs>
```

you can also use: --no-instrument-reg= and --only-instrument-reg=


