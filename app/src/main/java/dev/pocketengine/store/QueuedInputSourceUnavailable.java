package dev.pocketengine.store;

/** Exact native refusal before claiming a queued body whose original message cannot be verified. */
public final class QueuedInputSourceUnavailable extends IllegalStateException {
    public QueuedInputSourceUnavailable(Exception cause) {
        super("待处理输入的消息版本或来源已变化。请在待处理输入中核对；没有采用旧内容，也没有重发已发生的动作。", cause);
    }
}
