package cucumber.cli.runner.plugin;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.cfg4j.provider.GenericType;
import org.joda.time.DateTime;

import cucumber.runtime.model.CucumberFeature;
import cucumber.runtime.model.CucumberTagStatement;
import net.masterthought.cucumber.Configuration;
import net.masterthought.cucumber.ReportBuilder;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.reflect.ClassPath;
import com.intuit.karate.cucumber.KarateFeature;
import com.intuit.karate.cucumber.KarateJunitAndJsonReporter;
import com.intuit.karate.cucumber.KarateRuntime;
import com.intuit.karate.filter.TagFilterException;

/**
 * Goal which invokes custom cucumber runner.
 */
@Mojo(name = "runcukes", threadSafe = true, defaultPhase = LifecyclePhase.TEST, requiresDependencyResolution = ResolutionScope.TEST, requiresProject = true, requiresDependencyCollection = ResolutionScope.TEST)
public class CucumberRunnerMojo extends AbstractMojo {
	/**
	 * Location of the file.
	 */
	@Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
	private File outputDirectory;

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject mavenProject;

	@Parameter(defaultValue = "${project.testClasspathElements}", readonly = true)
	private List<String> additionalClasspathElements = new ArrayList<>();

	@Parameter(property = "project.testClasspathElements", required = true, readonly = true)
	private List<String> testClasspathElements;

	@Parameter(property = "project.compileClasspathElements", required = true, readonly = true)
	private List<String> compileClasspathElements;
	
	@Parameter(property = "project.runtimeClasspathElements", required = true, readonly = true)
	private List<String> runtimeClasspathElements;

	private ExecutorService featureRunner = null;
	private List<CompletableFuture<Supplier<Byte>>> featureStatus = new ArrayList<>();

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {

		logConfiguration();
		getLog().debug(compileClasspathElements.toString());
		getLog().debug(runtimeClasspathElements.toString());
		getLog().debug(testClasspathElements.toString());
		ClassLoader classLoader = CucumberRunnerMojo.class.getClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(getClassLoader(classLoader));
		} catch (DependencyResolutionRequiredException e1) {
			getLog().error("Dependency resolution failed!! - classloader not set");
			getLog().error(e1);
		}
		getLog().info("Initializing properties...");
		listLoadedClasses(Thread.currentThread().getContextClassLoader());
		PropertyLoader.init();

		List<ExecutionModes> parallelModes = PropertyLoader.provider.getProperty("parallelmode",
				new GenericType<List<ExecutionModes>>() {
				});
		getLog().info("Initializing ThreadPool...");
		featureRunner = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2,
				Executors.privilegedThreadFactory());
		getLog().info("Creating output directories...");
		String[] opDir = { outputDirectory.getPath() + "/cucumber-reports/api",outputDirectory.getPath() + "/cucumber-reports/ui" };
		for (String string : opDir) {
			try {
				FileUtils.forceMkdir(new File(string));
			} catch (IOException e) {
				getLog().error("Report folder creation error:" + e.getMessage());
			}
		}
		getLog().info("Selecting ExecutionModes...");
		for (ExecutionModes em : parallelModes) {
			try {
				switch (em) {
				case API_TAG_PARALLEL:
					run_api_parallel(true);
					break;
				case API_FEATURE_SEQUENTIAL:
					run_api_features_sequential();
					break;
				case API_FEATURE_PARALLEL:
					run_api_parallel(false);
					break;
				case UI_TAG_PARALLEL:
					run_ui_tags_in_parallel();
					break;
				case UI_FEATURE_PARALLEL:
					run_ui_features_in_parallel();
					break;
				case UI_FEATURE_SEQUENTIAL:
					run_ui_sequentially();
					break;

				}
				CompletableFuture.allOf(featureStatus.toArray(new CompletableFuture[featureStatus.size()])).get();
				featureRunner.shutdown();
				Boolean isAutoGenerateReport = PropertyLoader.provider.getProperty("generatereport", Boolean.class);
				if (null != isAutoGenerateReport && isAutoGenerateReport)
					generateReport();

			} catch (IOException | InterruptedException | TagFilterException | ExecutionException e) {
				// TODO Auto-generated catch block
				getLog().error(e);
				getLog().error("Shutting down all threads");
				featureRunner.shutdownNow();
			} finally {
				if (classLoader != null) {
					Thread.currentThread().setContextClassLoader(classLoader);
				}
			}

		}
	}

	private void run_api_parallel(Boolean isTags) throws IOException, InterruptedException, TagFilterException {
		List<String> features = getfilelist(mavenProject.getBasedir().getAbsolutePath() + File.separator
				+ PropertyLoader.provider.getProperty("apifeaturefilepath", String.class), "feature");
		for (String feature : features) {
			executeApiTests(feature, isTags);
		}
	}

	private void run_api_features_sequential() throws IOException {

		List<String> features = getfilelist(mavenProject.getBasedir().getAbsolutePath() + File.separator
				+ PropertyLoader.provider.getProperty("apifeaturefilepath", String.class), "feature");
		for (String string : features) {
			File file = new File(string);
			KarateFeature kf = new KarateFeature(file);
			getLog().debug("Executing feature file:" + file.getPath());
			KarateJunitAndJsonReporter reporter = new KarateJunitAndJsonReporter(file.getPath(),
					outputDirectory.getPath() + "/cucumber-reports/api/" + file.getName() + ".json");
			KarateRuntime runtime = kf.getRuntime(reporter);

			kf.getFeature().run(reporter, reporter, runtime);
			reporter.done();
		}
	}

	private void run_ui_sequentially() throws IOException, InterruptedException {
		List<String> arguments = new ArrayList<String>();
		arguments.addAll(getfilelist(mavenProject.getBasedir().getAbsolutePath() + File.separator
				+ PropertyLoader.provider.getProperty("featurefilepath", String.class), "feature"));
		arguments.add("--format");
		arguments.add("pretty");
		arguments.add("--format");
		arguments.add("json:" + outputDirectory.getPath() + "/cucumber-reports/ui/"
				+ DateTime.now().toDateTimeISO().toString("hhmmssddMMyyyy") + ".json");
		List<String> tags = PropertyLoader.provider.getProperty("tagstorun", new GenericType<List<String>>() {
		});
		for (String runnabletags : tags) {
			if (!runnabletags.contains("none")) {
				arguments.add("--tags");

				// arguments.add("@" + runnabletags);
				arguments.add(runnabletags);
			}
		}
		List<String> gluepackages = PropertyLoader.provider.getProperty("gluedpackages",
				new GenericType<List<String>>() {
				});
		for (String packages : gluepackages) {
			if (!packages.contains("none")) {
				arguments.add("--glue");
				arguments.add(packages);
			}
		}

		final String[] argv = arguments.toArray(new String[0]);
		executeUITests(argv);

	}

	public void run_ui_tags_in_parallel() throws IOException, InterruptedException {

		List<String> tags = PropertyLoader.provider.getProperty("tagstorun", new GenericType<List<String>>() {
		});
		for (String runnabletags : tags) {
			List<String> arguments = new ArrayList<String>();

			arguments.addAll(getfilelist(mavenProject.getBasedir().getAbsolutePath() + File.separator
					+ PropertyLoader.provider.getProperty("featurefilepath", String.class), "feature"));
			arguments.add("--format");
			arguments.add("pretty");
			arguments.add("--format");
			arguments.add("json:" + outputDirectory.getPath() + "/cucumber-reports/ui/"+runnabletags+"_"
					+ DateTime.now().toDateTimeISO().toString("hhmmssddMMyyyy") + ".json");
			if (!runnabletags.contains("none")) {
				arguments.add("--tags");
				// arguments.add("@" + runnabletags);
				arguments.add(runnabletags);
			}

			List<String> gluepackages = PropertyLoader.provider.getProperty("gluedpackages",
					new GenericType<List<String>>() {
					});
			for (String packages : gluepackages) {
				if (!packages.contains("none")) {
					arguments.add("--glue");
					arguments.add(packages);
				}
			}

			final String[] argv = arguments.toArray(new String[0]);

			executeUITests(argv);
		}
	}

	public void run_ui_features_in_parallel() throws IOException, InterruptedException {

		List<String> features = getfilelist(mavenProject.getBasedir().getAbsolutePath() + File.separator
				+ PropertyLoader.provider.getProperty("featurefilepath", String.class), "feature");
		for (String feature : features) {
			System.out.println("Current Feature: " + feature);
			List<String> arguments = new ArrayList<String>();
			arguments.add(feature);
			arguments.add("--format");
			arguments.add("pretty");
			arguments.add("--format");
			arguments.add("json:" + outputDirectory.getPath() + "/cucumber-reports/ui/"+feature.substring(feature.lastIndexOf('/')+1,feature.length())+"_"
					+ DateTime.now().toDateTimeISO().toString("hhmmssddMMyyyy") + ".json");

			List<String> gluepackages = PropertyLoader.provider.getProperty("gluedpackages",
					new GenericType<List<String>>() {
					});
			for (String packages : gluepackages) {
				if (!packages.contains("none")) {
					arguments.add("--glue");
					arguments.add(packages);
				}
			}
			System.out.println("Arguments sent:" + arguments);
			final String[] argv = arguments.toArray(new String[0]);
			executeUITests(argv);
		}
	}

	public void executeUITests(final String[] argv) throws InterruptedException {
		// TODO need to find an alternate runner which has better error handline
		getLog().debug("Arguments set to ui tests");
		for (String string : argv) {
			getLog().debug(string);
		}
		BiFunction<String[], Boolean, Supplier<Byte>> executeUITests = (args, tagOnly) -> {
			try {
				cucumber.api.cli.Main.run(args, Thread.currentThread().getContextClassLoader());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				getLog().error("Exception occured: " + e.getMessage());
				return () -> 0x1;
			}
			return () -> 0x0;
		};

		executeTests(argv, false, executeUITests);
	}

	public void executeApiTests(final String featureFile, final Boolean isTags)
			throws InterruptedException, IOException {

		getLog().info("Executing API tests...");
		BiFunction<String[], Boolean, Supplier<Byte>> executeAPITests = (args, tagOnly) -> {
			File file = new File(args[0]);

			KarateFeature kf = new KarateFeature(file);
			if (tagOnly)
				filterOnTags(kf.getFeature());
			if (!kf.getFeature().getFeatureElements().isEmpty()) {
				KarateJunitAndJsonReporter reporter = null;
				try {
					getLog().debug("Executing feature file:" + file.getPath());
					reporter = new KarateJunitAndJsonReporter(file.getPath(),
							outputDirectory.getPath() + "/cucumber-reports/api/" + file.getName() + ".json");
					KarateRuntime runtime = kf.getRuntime(reporter);
					kf.getFeature().run(reporter, reporter, runtime);
					reporter.done();
					getLog().info("Exit status from karate runner:" + runtime.exitStatus());
					return () -> runtime.exitStatus();
				} catch (Exception e) {
					getLog().error(e);
				}

			}
			return () -> 0x0;
		};
		executeTests(new String[] { featureFile }, isTags, executeAPITests);
	}

	public void executeTests(final String[] args, final Boolean isTags,
			BiFunction<String[], Boolean, Supplier<Byte>> executionFunction) {
		getLog().info("Executing tests on:" + Thread.currentThread());
		CompletableFuture<Supplier<Byte>> future = CompletableFuture
				.supplyAsync(() -> executionFunction.apply(args, isTags), featureRunner);
		featureStatus.add(future);
	}

	private void filterOnTags(CucumberFeature feature) {
		final List<CucumberTagStatement> featureElements = feature.getFeatureElements();
		List<String> tags = PropertyLoader.provider.getProperty("tagstorun", new GenericType<List<String>>() {
		});

		System.err.println("Filtering tags: " + tags.toString());
		for (Iterator<CucumberTagStatement> iterator = featureElements.iterator(); iterator.hasNext();) {
			CucumberTagStatement cucumberTagStatement = iterator.next();
			final boolean isFiltered = cucumberTagStatement.getGherkinModel().getTags().stream()
					.anyMatch(t -> tags.contains(t.getName()))
					|| feature.getGherkinFeature().getTags().stream().anyMatch(t -> tags.contains(t.getName()));
			getLog().debug(
					"Feature status:" + isFiltered + " For " + cucumberTagStatement.getVisualName() + " of feature "
							+ feature.getPath() + " At line: " + cucumberTagStatement.getGherkinModel().getLine());
			if (!isFiltered) {
				getLog().debug("skipping feature element " + cucumberTagStatement.getVisualName() + " of feature "
						+ feature.getPath() + " At line: " + cucumberTagStatement.getGherkinModel().getLine());
				iterator.remove();
			}
		}
	}

	public List<String> getfilelist(String pathname, String type) throws IOException {
		return FileUtils
				.listFilesAndDirs(new File(pathname).getAbsoluteFile(), TrueFileFilter.INSTANCE,
						DirectoryFileFilter.DIRECTORY)
				.stream().filter(file -> file.getName().endsWith(type)).map(f -> f.getPath().replace("\\", "/"))
				.collect(Collectors.toList());
	}

	private void generateReport() throws IOException {
		List<String> reportfiles = getfilelist(outputDirectory.getPath() + "/cucumber-reports", "json");
		Configuration cfg = new Configuration(outputDirectory,
				PropertyLoader.provider.getProperty("buildname", String.class));
		cfg.setBuildNumber(PropertyLoader.provider.getProperty("buildnumber", String.class));
		ReportBuilder rp = new ReportBuilder(reportfiles, cfg);
		rp.generateReports();
	}

	private ClassLoader getClassLoader(ClassLoader classLoader)
			throws MojoExecutionException, DependencyResolutionRequiredException {
		List<URL> classpath = new ArrayList<URL>();
		Set<String> cumumativeClassPath = new HashSet<String>();
		cumumativeClassPath.addAll(compileClasspathElements);
		cumumativeClassPath.addAll(testClasspathElements);
		cumumativeClassPath.addAll(runtimeClasspathElements);
		for (String pcEntry : cumumativeClassPath) {
			try {
				File f = new File(pcEntry);
				classpath.add(f.toURI().toURL());
				getLog().debug("Added to classpath " + pcEntry);
			} catch (Exception e) {
				getLog().debug("Unable to load " + pcEntry + "Error:" + e.getMessage());
			}
		}

		if (additionalClasspathElements != null) {
			for (String element : additionalClasspathElements) {
				try {
					File f = new File(element);
					classpath.add(f.toURI().toURL());
					getLog().debug("Added to classpath " + element);
				} catch (Exception e) {
					throw new MojoExecutionException("Error setting classpath " + element + " " + e.getMessage());
				}
			}
		}

		URL[] urls = classpath.toArray(new URL[classpath.size()]);
		return new URLClassLoader(urls, classLoader);
	}

	private void logConfiguration() {
		getLog().debug("Output dir: " + outputDirectory.getAbsolutePath());
		getLog().debug("classpathElements: " + additionalClasspathElements);

	}

	public void listLoadedClasses(ClassLoader byClassLoader) {
		Class<? extends ClassLoader> clKlass = byClassLoader.getClass();
		getLog().debug("Classloader: " + clKlass.getCanonicalName());
		try {
			ClassPath.from(byClassLoader).getTopLevelClasses().stream().forEach(ci -> {
				getLog().debug("Class loaded: " + ci.getSimpleName());
			});
		} catch (IOException e) {
			// TODO Auto-generated catch block
			getLog().error("Error in classload query" + e.getMessage());
			// getLog().error(e);
		}
	}

}
