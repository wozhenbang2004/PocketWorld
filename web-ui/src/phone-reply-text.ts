// ConversationPhoneHandoff.summaryContent appends this navigation hint; it is not part of the report.
const truncatedNotice='\n\n（结果较长；完整结果请查看原手机任务。）';
export const originalPhoneReplyText=(text:string,truncated:boolean):string=>
  !truncated?text:text.endsWith(truncatedNotice)?text.slice(0,-truncatedNotice.length):'';
