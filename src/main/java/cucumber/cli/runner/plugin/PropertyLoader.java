package cucumber.cli.runner.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cfg4j.provider.ConfigurationProvider;
import org.cfg4j.provider.ConfigurationProviderBuilder;
import org.cfg4j.source.ConfigurationSource;
import org.cfg4j.source.compose.MergeConfigurationSource;
import org.cfg4j.source.context.filesprovider.ConfigFilesProvider;
import org.cfg4j.source.files.FilesConfigurationSource;
import org.cfg4j.source.system.SystemPropertiesConfigurationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PropertyLoader {
	public static ConfigurationProvider provider = null;
	private static Logger log = LoggerFactory.getLogger(PropertyLoader.class);

	public static void init() {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		String[] propfiles = { "tests.properties", "browser.properties", "report.properties", "mailer.properties" };
		List<Path> path = new ArrayList<>();
		Arrays.stream(propfiles).forEach(file -> {
			try {
				path.add(Paths.get(cl.getResource(file).toURI()));
			} catch (Exception e) {
				log.error("File:" + file + " not found.");
			}
		});
		ConfigFilesProvider configFilesProvider = () -> path;
		ConfigurationSource source1 = new FilesConfigurationSource(configFilesProvider);
		ConfigurationSource source2 = new SystemPropertiesConfigurationSource();
		ConfigurationSource source = new MergeConfigurationSource(source1, source2);
		// ReloadStrategy reloadStrategy = new
		// PeriodicalReloadStrategy(10,TimeUnit.SECONDS);
		provider = new ConfigurationProviderBuilder().withConfigurationSource(source).build();
		// .withReloadStrategy(reloadStrategy).build();
	}

}