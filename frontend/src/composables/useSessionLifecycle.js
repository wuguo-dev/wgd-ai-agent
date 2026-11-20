import { onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth.js';
export function useSessionLifecycle() {
    const auth = useAuthStore();
    const router = useRouter();
    const sessionError = ref('');
    let timer;
    async function refresh() {
        if (document.visibilityState === 'hidden' || auth.loggingOut) return;
        try {
            await auth.fetchCurrentUser(true);
            sessionError.value = '';
        } catch (error) {
            sessionError.value = error.message;
        }
    }
    async function logout() {
        sessionError.value = '';
        try {
            await auth.logout();
            await router.replace('/login');
        } catch (error) {
            sessionError.value = error.message;
        }
    }
    onMounted(() => {
        timer = window.setInterval(refresh, 60000);
        window.addEventListener('focus', refresh);
        document.addEventListener('visibilitychange', refresh);
    });
    onUnmounted(() => {
        window.clearInterval(timer);
        window.removeEventListener('focus', refresh);
        document.removeEventListener('visibilitychange', refresh);
    });
    return { auth, sessionError, logout };
}
