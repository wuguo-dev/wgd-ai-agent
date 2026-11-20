import request from './request.js';

export const getCategories = () => request.get('/community/categories');
export const getQuestions = (params) =>
    request.get('/community/questions', { params });
export const createQuestion = (input) =>
    request.post('/community/questions', input);
export const getQuestion = (id) => request.get(`/community/questions/${id}`);
export const getAnswers = (id, params) =>
    request.get(`/community/questions/${id}/answers`, { params });
export const createAnswer = (id, content) =>
    request.post(`/community/questions/${id}/answers`, { content });
export const setFavorite = (id, favorite) =>
    favorite
        ? request.post(`/community/questions/${id}/favorite`)
        : request.delete(`/community/questions/${id}/favorite`);
export const getMyRecords = (kind, params) =>
    request.get(`/community/me/${kind}`, { params });
export const getPending = (kind, params) =>
    request.get(`/admin/${kind}`, {
        params: { status: 'PENDING', deleted: 'ACTIVE', ...params },
    });
export const reviewContent = (kind, id, approve, reason) =>
    request.post(
        `/admin/${kind}/${id}/${approve ? 'approve' : 'reject'}`,
        approve ? undefined : { reason }
    );
export const getAdminCategories = () => request.get('/admin/categories');
export const saveCategory = (id, input) =>
    id
        ? request.put(`/admin/categories/${id}`, input)
        : request.post('/admin/categories', input);
export const setCategoryStatus = (id, status) =>
    request.patch(`/admin/categories/${id}/status`, { status });
