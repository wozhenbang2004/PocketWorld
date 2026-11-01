package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;

/** Two registered native transports share the original Skill plan/TaskStore once
 * boundary. This is not a backend selected by model JSON or a second ledger. */
public interface NativeSkillBackend extends SkillExecution.Backend,AutoCloseable {
    @FunctionalInterface interface DispatchCheck {void check(SkillExecution.Plan plan,SkillExecution.Grant grant,RunControl original)throws Exception;}
    void bindDispatchCheck(DispatchCheck check);
    SkillExecution.Receipt query(CompanionSkillBackend.ReceiptRef original)throws Exception;
    void requestCancel(CompanionSkillBackend.ReceiptRef original)throws Exception;
    SkillExecution.Receipt cancelOriginal(CompanionSkillBackend.ReceiptRef original)throws Exception;
    CompanionSkillBackend.ArtifactInventory artifacts(CompanionSkillBackend.ReceiptRef original)throws Exception;
    @Override void close();
}
