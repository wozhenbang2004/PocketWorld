package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native reviewed executable mapping; a model's executable name only selects an existing entry. */
public final class RuntimeTool {
    public final String name,identity;
    private final File executable;
    private final String sha256,version,license;
    private final List<String> prefix,versionArguments;
    public RuntimeTool(String name,File approvedExecutable,String expectedSha256,String version,String license,
            List<String> fixedArguments,List<String> versionArguments) throws Exception {
        if(!name.matches("[A-Za-z0-9_-]{1,80}") || !expectedSha256.matches("[a-f0-9]{64}")
                || version.isEmpty() || license.isEmpty())throw new IllegalArgumentException("runtime_tool_identity_invalid");
        this.name=name;executable=approvedExecutable.getCanonicalFile();sha256=expectedSha256;this.version=version;this.license=license;
        prefix=List.copyOf(fixedArguments);this.versionArguments=List.copyOf(versionArguments);
        identity=ProjectSources.digest(new JSONObject().put("name",name).put("sha256",sha256).put("version",version)
                .put("license",license).put("prefix",new JSONArray(prefix)).put("versionArguments",new JSONArray(this.versionArguments)));
    }
    public JSONObject describe() throws Exception {
        boolean present=executable.isFile() && executable.canExecute();
        boolean verified=present && RuntimeFiles.sha256(executable).equals(sha256);
        return new JSONObject().put("name",name).put("identity",identity).put("version",version).put("license",license)
                .put("installed",present).put("hashVerified",verified).put("sha256",sha256);
    }
    public List<String> command(List<String> arguments) throws Exception {
        if(!describe().getBoolean("hashVerified"))throw new SecurityException("runtime_tool_missing_or_changed");
        List<String> command=new ArrayList<>();command.add(executable.getPath());command.addAll(prefix);command.addAll(arguments);
        if(command.size()>256)throw new IllegalArgumentException("runtime_tool_arguments_too_many");return command;
    }
    public List<String> probeCommand() throws Exception {return command(versionArguments);}
    File executableDirectory() {return executable.getParentFile();}
}
