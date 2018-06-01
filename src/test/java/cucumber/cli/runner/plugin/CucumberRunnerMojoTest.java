package cucumber.cli.runner.plugin;

import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.plugin.testing.WithoutMojo;

import org.junit.Rule;
import static org.junit.Assert.*;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import com.intuit.karate.FileUtils;
import com.intuit.karate.cucumber.KarateFeature;
import com.intuit.karate.cucumber.KarateJunitAndJsonReporter;
import com.intuit.karate.cucumber.KarateRuntime;

import java.io.File;
import java.util.logging.Logger;

public class CucumberRunnerMojoTest {

	private static final Logger logger = Logger.getLogger("test");

	private boolean contains(String reportPath, String textToFind) {
		String contents = FileUtils.toString(new File(reportPath));
		return contents.contains(textToFind);
	}

	public static KarateJunitAndJsonReporter run(File file, String reportPath) throws Exception {
		KarateFeature kf = new KarateFeature(file);
		KarateJunitAndJsonReporter reporter = new KarateJunitAndJsonReporter(file.getPath(), reportPath);
		KarateRuntime runtime = kf.getRuntime(reporter);
		kf.getFeature().run(reporter, reporter, runtime);
		reporter.done();
		return reporter;
	}
	
	@Test 
    public void testScenario() throws Exception {
        //String reportPath = "target/scenario.json";
        //File file = new File("C:\\Users\\suhail_sullad\\digicert-workspace\\plugintest\\src\\test\\resources\\features\\api\\test.feature");
       // run(file, reportPath);
       // assertTrue(contains(reportPath, "Then match b == { foo: 'bar'}"));
    }

}
