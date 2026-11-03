package dev.pocketengine.phone.diagnostics;

/** Native owner entry supplies only this app and its real process user, never an arbitrary business target. */
public final class PhonePracticeRequests {
    private PhonePracticeRequests() { }
    public static String goal(String ownPackage,int ownerUser) {
        new dev.pocketengine.phone.access.AppCopyKey(ownPackage,ownerUser);
        return "这是主人明确开始的PocketWorld自有页面兼容演练，沿当前原手机任务、原显示、原权限、原预算执行。"
                +"先调用phone_practice_target，packageName="+ownPackage+"，userId="+ownerUser+"；目标必须只为自有PW_DIAG_TARGET_V1页面。"
                +"读取当前真实树，依次点击PW_DIAG_CLICK按钮并核对PW_DIAG_CLICK_COUNT增加；在description为PW_DIAG_INPUT的唯一普通字段输入PocketWorld ASCII 42并回读，再输入口袋世界中文演练42并回读；"
                +"对PW_DIAG_LIST做一次原生节点滚动并核对实际可见行变化；使用原phone_wait等待至少3500毫秒再观察PW_DIAG_TICK变化；"
                +"若原任务已允许本地取图，调用phone_practice_frame_check，只在本地检查当前自有目标的真实帧，不上传图片或修改许可；"
                +"然后原phone_back并观察确实离开测试页，最后phone_practice_result读取本任务本次十二项证据。"
                +"逐项如实报告passed/failed/unknown；accepted按钮、已连接或预期值不证明通过。权限、身份、输入法、像素或平台能力不明就保留unknown/partial并交主人核对，未知动作不重发。"
                +"不打开其他业务App，不触及账号、发送、保存、付款、权限确认，不自动切主屏或新建执行器；暂停、停止和主人新话仍由原任务接收。";
    }
}
