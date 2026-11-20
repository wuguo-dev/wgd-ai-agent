import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { currentStudent, loginStudent, logoutStudent } from '../api/auth.js';

export const useAuthStore = defineStore('auth', () => {
    const user = ref(null);
    const initialized = ref(false);
    const loggingIn = ref(false);
    const loggingOut = ref(false);
    const initializationError = ref('');
    const isAuthenticated = computed(() => user.value !== null);
    let initialization = null;
    let generation = 0;

    function clear() {
        generation++;
        user.value = null;
        initializationError.value = '';
        initialized.value = true;
    }

    function fetchCurrentUser(force = false) {
        if (initialization) return initialization;
        if (initialized.value && !force) return Promise.resolve(user.value);
        const startedGeneration = generation;
        initializationError.value = '';
        initialization = currentStudent().then(current => {
            if (startedGeneration === generation) {
                user.value = current;
                initialized.value = true;
            }
            return user.value;
        }).catch(error => {
            if (error.status === 401) return null;
            if (startedGeneration === generation) initializationError.value = error.message;
            throw error;
        }).finally(() => { initialization = null; });
        return initialization;
    }

    async function login(username, password) {
        if (loggingIn.value) return;
        loggingIn.value = true;
        generation++;
        try {
            user.value = await loginStudent(username, password);
            initialized.value = true;
            initializationError.value = '';
        } finally {
            loggingIn.value = false;
        }
    }

    async function logout() {
        if (loggingOut.value) return;
        loggingOut.value = true;
        generation++;
        try {
            await logoutStudent();
            clear();
        } catch (error) {
            if (error.status !== 401) throw error;
            clear();
        } finally {
            loggingOut.value = false;
        }
    }

    return { user, initialized, loggingIn, loggingOut, initializationError,
        isAuthenticated, clear, fetchCurrentUser, login, logout };
});
