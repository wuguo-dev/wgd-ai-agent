import { createApp } from 'vue';
import { createPinia } from 'pinia';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { ElConfigProvider } from 'element-plus';
import { h } from 'vue';
import App from './App.vue';
import { createAppRouter } from './router/index.js';
import { useAuthStore } from './stores/auth.js';
import { onUnauthorized } from './api/request.js';
import 'element-plus/theme-chalk/base.css';
import './assets/styles/variables.css';
import './assets/styles/global.css';

const app = createApp({ render: () => h(ElConfigProvider, { locale: zhCn }, () => h(App)) });
const pinia = createPinia();
app.use(pinia);
const auth = useAuthStore(pinia);
const router = createAppRouter(auth);
onUnauthorized(() => {
    auth.clear();
    const route = router.currentRoute.value;
    if (route.meta.requiresAuth) {
        void router.replace({ name: 'login', query: { redirect: route.fullPath, reason: 'expired' } });
    }
});
app.use(router);
await router.isReady();
app.mount('#app');
