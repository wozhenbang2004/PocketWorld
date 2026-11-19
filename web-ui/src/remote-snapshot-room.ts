type Row=Record<string,any>;
export const captureBytes=524288, memberBytes=262144;
export type SnapshotRoom={retainedCount:number;remainingCount:number;remainingBytes:number};
/** An optional observation from that exact job. A legacy server is unknown, not empty. */
export function originalSnapshotRoom(result:Row,source:Row):SnapshotRoom|null{
  const r=result.snapshotRoom;
  if(!r||r.protocol!=='original_result_snapshot_room_v1'||r.maximumCount!==4||r.maximumBytes!==1048576||r.captureMaximumBytes!==captureBytes)return null;
  if(!['jobId','jobOperationId','environmentId','worker','workspaceIdentity'].every(k=>typeof source[k]==='string'&&source[k]&&r[k]===source[k]&&r[k]===result[k==='jobOperationId'?'operationId':k]))return null;
  if(!Number.isSafeInteger(r.retainedCount)||r.retainedCount<0||r.retainedCount>4||r.remainingCount!==4-r.retainedCount||
    !Number.isSafeInteger(r.retainedBytes)||r.retainedBytes<0||r.retainedBytes>Math.min(1048576,r.retainedCount*captureBytes)||r.remainingBytes!==1048576-r.retainedBytes)return null;
  return {retainedCount:r.retainedCount,remainingCount:r.remainingCount,remainingBytes:r.remainingBytes};
}
export function selectionBlock(file:Row,count:number,bytes:number,room:SnapshotRoom|null):string{
  if(file.kind==='directory')return '';
  if(file.kind!=='file')return '不是可冻结的普通文件';
  if(!Number.isSafeInteger(file.bytes)||file.bytes<0)return '文件大小无法核对';
  if(file.bytes>memberBytes)return '超过单文件 256 KiB，只列信息';
  if(file.selectable!==true)return '原主机未允许选取这份文件';
  if(room?.remainingCount===0)return '该原作业的快照位已满';
  if(count>=8)return '本次已选满 8 个文件';
  if(bytes+file.bytes>captureBytes)return '加入后会超过本次 512 KiB';
  if(room&&bytes+file.bytes>room.remainingBytes)return '加入后会超过这个原作业的剩余字节';
  return '';
}
