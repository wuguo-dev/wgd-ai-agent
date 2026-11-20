import { createRouter, createWebHistory } from 'vue-router';

export const routes = [
    { path: '/', redirect: '/login' },
    {
        path: '/login',
        component: () => import('../layouts/AuthLayout.vue'),
        children: [
            {
                path: '',
                name: 'login',
                component: () => import('../views/auth/LoginView.vue'),
                meta: { guestOnly: true, title: '账号登录' },
            },
        ],
    },
    {
        path: '/register',
        component: () => import('../layouts/AuthLayout.vue'),
        children: [
            {
                path: '',
                name: 'register',
                component: () => import('../views/auth/RegisterView.vue'),
                meta: { guestOnly: true, title: '学生注册' },
            },
        ],
    },
    {
        path: '/community',
        component: () => import('../layouts/StudentLayout.vue'),
        children: [
            {
                path: '',
                name: 'community',
                component: () => import('../views/community/HomeView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['STUDENT', 'ADMIN'],
                    title: '学生社区',
                },
            },
            {
                path: 'questions/new',
                name: 'question-create',
                component: () =>
                    import('../views/community/QuestionCreateView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['STUDENT', 'ADMIN'],
                    title: '发布问题',
                },
            },
            {
                path: 'questions/:id',
                name: 'question-detail',
                component: () =>
                    import('../views/community/QuestionDetailView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['STUDENT', 'ADMIN'],
                    title: '问题详情',
                },
            },
        ],
    },
    {
        path: '/agent',
        component: () => import('../layouts/StudentLayout.vue'),
        children: [
            {
                path: 'chat',
                name: 'agent-chat',
                component: () => import('../views/agent/AgentChatView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['STUDENT', 'ADMIN'],
                    title: 'AI 智能问答',
                },
            },
        ],
    },
    {
        path: '/profile',
        component: () => import('../layouts/StudentLayout.vue'),
        children: [
            {
                path: '',
                name: 'profile',
                component: () => import('../views/profile/ProfileView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['STUDENT', 'ADMIN'],
                    title: '个人中心',
                },
            },
        ],
    },
    {
        path: '/admin',
        component: () => import('../layouts/AdminLayout.vue'),
        children: [
            { path: '', redirect: '/admin/dashboard' },
            {
                path: 'dashboard',
                name: 'admin-dashboard',
                component: () => import('../views/admin/DashboardView.vue'),
                meta: { requiresAuth: true, roles: ['ADMIN'], title: '控制台' },
            },
            {
                path: 'users',
                name: 'admin-users',
                component: () => import('../views/admin/UserManageView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['ADMIN'],
                    title: '用户管理',
                },
            },
            {
                path: 'operation-logs',
                name: 'admin-logs',
                component: () => import('../views/admin/OperationLogView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['ADMIN'],
                    title: '操作日志',
                },
            },

            {
                path: 'questions',
                name: 'review-questions',
                component: () => import('../views/admin/ReviewView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['ADMIN'],
                    reviewKind: 'questions',
                    title: '问题管理',
                },
            },
            {
                path: 'answers',
                name: 'review-answers',
                component: () => import('../views/admin/ReviewView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['ADMIN'],
                    reviewKind: 'answers',
                    title: '回答管理',
                },
            },
            {
                path: 'categories',
                name: 'admin-categories',
                component: () =>
                    import('../views/admin/CategoryManageView.vue'),
                meta: {
                    requiresAuth: true,
                    roles: ['ADMIN'],
                    title: '分类管理',
                },
            },
        ],
    },
    {
        path: '/forbidden',
        component: () => import('../layouts/StudentLayout.vue'),
        children: [
            {
                path: '',
                name: 'forbidden',
                component: () => import('../views/ForbiddenView.vue'),
                meta: { requiresAuth: true, title: '权限不足' },
            },
        ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/login' },
];

export function defaultHomePath(role) {
    return role === 'ADMIN' ? '/admin/dashboard' : '/community';
}

// Resolve only registered protected routes. Full URLs, unknown paths and guest routes are excluded.
export function safeReturnPath(router, candidate, role = 'STUDENT') {
    const fallback = defaultHomePath(role);
    if (
        typeof candidate !== 'string' ||
        !candidate.startsWith('/') ||
        candidate.startsWith('//') ||
        /[\\\s]/.test(candidate)
    )
        return fallback;
    const resolved = router.resolve(candidate);
    const allowed = !resolved.meta.roles || resolved.meta.roles.includes(role);
    return resolved.meta.requiresAuth && allowed ? resolved.fullPath : fallback;
}

export function createAppRouter(auth) {
    const router = createRouter({
        history: createWebHistory(import.meta.env.BASE_URL),
        routes,
    });
    router.beforeEach(async (to) => {
        try {
            await auth.fetchCurrentUser();
        } catch {
            if (to.meta.requiresAuth)
                return { name: 'login', query: { redirect: to.fullPath } };
        }
        if (to.meta.requiresAuth && !auth.isAuthenticated) {
            return { name: 'login', query: { redirect: to.fullPath } };
        }
        if (
            to.meta.roles &&
            auth.user &&
            !to.meta.roles.includes(auth.user.role)
        ) {
            return { name: 'forbidden' };
        }
        if (to.meta.guestOnly && auth.isAuthenticated) {
            return safeReturnPath(router, to.query.redirect, auth.user.role);
        }
        return true;
    });
    router.afterEach((to) => {
        document.title = `${to.meta.title || '知识问答'} · 知识问答`;
    });
    return router;
}
