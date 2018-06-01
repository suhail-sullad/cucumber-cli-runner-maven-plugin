package cucumber.cli.runner.plugin;

public enum ExecutionModes {

	API_TAG_PARALLEL("apitagsparallel"), API_FEATURE_PARALLEL("apifeaturesparallel"), API_FEATURE_SEQUENTIAL(
			""), API_TAG_SEQUENTIAL(""), UI_TAG_PARALLEL(""), UI_FEATURE_PARALLEL(""), UI_FEATURE_SEQUENTIAL("");

	String mode_string;

	ExecutionModes(String mode) {
		this.mode_string = mode;
	}

}