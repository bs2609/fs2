package client.platform;

import java.util.Collections;
import java.util.List;

public class Linux implements PlatformOS {
	
	@Override
	public String getJVMExecutablePath() {
		return System.getProperty("java.home")+"/bin/java";
	}
	
	@Override
	public List<TaskDescription> getActiveTasks() {
		return Collections.emptyList();
	}

}
