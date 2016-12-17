# jscoverproxy-maven-plugin

A maven plugin for replacing saga-maven-plugin, as it does not generate the correct coverage anymore when used with the jasmine-maven-plugin.


# Example of use:

See: http://searls.github.io/jasmine-maven-plugin/code-coverage.html : replace the plugin block for saga-maven-plugin with:


```xml
<plugin>
	<groupId>ch.digitalfondue.jscover</groupId>
	<artifactId>jscoverproxy-maven-plugin</artifactId>
	<version>1.0</version>
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
		<jsSrcDir>src/main/webapp/app</jsSrcDir>
		<generateXMLSUMMARY>true</generateXMLSUMMARY>
		<generateLCOV>true</generateLCOV>
	</configuration>
</plugin>
```