# jscoverproxy-maven-plugin


[![Maven Central](https://img.shields.io/maven-central/v/ch.digitalfondue.jscover/jscoverproxy-maven-plugin.svg)](http://search.maven.org/#search%7Cga%7C1%7Ca%3A%22jscoverproxy-maven-plugin%22)


A maven plugin for replacing saga-maven-plugin, as it does not generate the correct coverage anymore when used with the jasmine-maven-plugin.


# Example of use:

See: http://searls.github.io/jasmine-maven-plugin/code-coverage.html : replace the plugin block for saga-maven-plugin with:


```xml
<plugin>
	<groupId>ch.digitalfondue.jscover</groupId>
	<artifactId>jscoverproxy-maven-plugin</artifactId>
	<version>1.1.0</version>
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
		<instrumentPathArgs>
			<arg>--no-instrument=webjars/</arg>
			<arg>--no-instrument=classpath/</arg>
			<arg>--no-instrument=spec/</arg>
		</instrumentPathArgs>
		<!-- Optional Args
		<JSVersion>180</JSVersion>
		<jsSrcDir>src/main/webapp/app</jsSrcDir>
		<includeUnloadedJS>true</includeUnloadedJS>
		<detectCoalesce>true</detectCoalesce>
		<generateXMLSUMMARY>true</generateXMLSUMMARY>
		<generateCOBERTURAXML>true</generateCOBERTURAXML>
		<generateLCOV>true</generateLCOV>
		-->
	</configuration>
</plugin>
```
