import request from './request.js';

/** @typedef {{ id: number, username: string, role: string }} UserInfo */

/** @returns {Promise<UserInfo>} */
export function loginStudent(username, password) {
    return request.post('/auth/login', { username, password });
}

/** @returns {Promise<UserInfo>} */
export function currentStudent() {
    return request.get('/auth/me');
}

export function logoutStudent() {
    return request.post('/auth/logout');
}

/** @returns {Promise<UserInfo>} */
export function registerStudent(username, password, confirmPassword) {
    return request.post('/auth/register', { username, password, confirmPassword });
}
