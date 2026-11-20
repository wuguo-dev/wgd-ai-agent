import request from './request.js';
export const getStats = () => request.get('/admin/dashboard/stats');
export const getManagedContent = (kind, params) =>
    request.get(`/admin/${kind}`, { params });
export const getManagedDetail = (kind, id) =>
    request.get(`/admin/${kind}/${id}`);
export const deleteContent = (kind, id, reason) =>
    request.delete(`/admin/${kind}/${id}`, { data: { reason } });
export const restoreContent = (kind, id) =>
    request.post(`/admin/${kind}/${id}/restore`);
export const getUsers = (params) => request.get('/admin/users', { params });
export const getUser = (id) => request.get(`/admin/users/${id}`);
export const changeUserStatus = (id, status, reason) =>
    request.patch(`/admin/users/${id}/status`, { status, reason });
export const getOperationLogs = (params) =>
    request.get('/admin/operation-logs', { params });
export const actionLabels = {
    QUESTION_APPROVE: '通过问题',
    QUESTION_REJECT: '驳回问题',
    QUESTION_DELETE: '删除问题',
    QUESTION_RESTORE: '恢复问题',
    ANSWER_APPROVE: '通过回答',
    ANSWER_REJECT: '驳回回答',
    ANSWER_DELETE: '删除回答',
    ANSWER_RESTORE: '恢复回答',
    USER_ENABLE: '启用学生',
    USER_DISABLE: '禁用学生',
    CATEGORY_CREATE: '新增分类',
    CATEGORY_UPDATE: '修改分类',
    CATEGORY_ENABLE: '启用分类',
    CATEGORY_DISABLE: '停用分类',
};
