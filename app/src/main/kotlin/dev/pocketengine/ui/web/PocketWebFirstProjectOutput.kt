package dev.pocketengine.ui.web

import dev.pocketengine.features.teams.TaskStoreTeamWriteHost
import dev.pocketengine.features.teams.TeamOwnerFileReading
import dev.pocketengine.features.teams.TeamsModule

/** The ordinary text command cannot consume a project-edition review handle, or vice versa. */
internal object PocketWebFirstProjectOutput {
    const val REVIEW = "teams.reviewFirstProjectText"
    const val CONFIRM = "teams.approveFirstProjectText"

    fun matches(token: TaskStoreTeamWriteHost.TextOutputApproval?, project: Boolean) =
        token != null && token.firstProject() == project

    fun open(module: TeamsModule, files: TeamOwnerFileReading, project: Boolean): TaskStoreTeamWriteHost.TextOutputApproval =
        if (project) module.reviewFirstProjectTextApproval(files) else module.reviewOwnerTextApproval(files)
}
