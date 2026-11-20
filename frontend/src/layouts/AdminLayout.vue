<script setup>
import { nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
    ElAlert,
    ElButton,
    ElDrawer,
    ElDropdown,
    ElDropdownItem,
    ElDropdownMenu,
} from 'element-plus';
import 'element-plus/es/components/drawer/style/css.mjs';
import 'element-plus/es/components/dropdown/style/css.mjs';
import 'element-plus/es/components/dialog/style/css.mjs';
import 'element-plus/es/components/select/style/css.mjs';
import 'element-plus/es/components/table/style/css.mjs';
import 'element-plus/es/components/pagination/style/css.mjs';
import 'element-plus/es/components/date-picker/style/css.mjs';
import '../assets/styles/community.css';
import '../assets/styles/admin.css';
import { useSessionLifecycle } from '../composables/useSessionLifecycle.js';
const route = useRoute();
const router = useRouter();
const { auth, sessionError, logout } = useSessionLifecycle();
const collapsed = ref(false);
const drawer = ref(false);
const menuButton = ref();
const menus = [
    { path: '/admin/dashboard', name: '控制台', symbol: '◫' },
    { path: '/admin/questions', name: '问题管理', symbol: '问' },
    { path: '/admin/answers', name: '回答管理', symbol: '答' },
    { path: '/admin/users', name: '用户管理', symbol: '人' },
    { path: '/admin/categories', name: '分类管理', symbol: '类' },
    { path: '/admin/operation-logs', name: '操作日志', symbol: '录' },
    { path: '/agent/chat', name: 'AI 智能问答', symbol: 'AI' },
];
async function restoreFocus() {
    await nextTick();
    menuButton.value?.$el?.focus();
}
watch(
    () => route.fullPath,
    () => {
        drawer.value = false;
    }
);
function command(value) {
    if (value === 'logout') logout();
    else router.push('/community');
}
</script>
<template>
    <div :class="['admin-shell', { collapsed }]">
        <aside class="admin-sidebar">
            <RouterLink to="/admin/dashboard" class="brand"
                ><span class="brand-mark">知</span
                ><span v-if="!collapsed">知识问答</span></RouterLink
            >
            <p v-if="!collapsed" class="sidebar-caption">社区管理工作台</p>
            <nav aria-label="后台导航">
                <RouterLink
                    v-for="item in menus"
                    :key="item.path"
                    :to="item.path"
                    :title="item.name"
                    :aria-label="item.name"
                    ><span class="nav-symbol" aria-hidden="true">{{
                        item.symbol
                    }}</span
                    ><span v-if="!collapsed">{{ item.name }}</span></RouterLink
                >
            </nav>
            <RouterLink
                class="sidebar-back"
                to="/community"
                aria-label="返回学生社区"
                >{{ collapsed ? '↗' : '返回学生社区 ↗' }}</RouterLink
            >
        </aside>
        <div class="admin-body">
            <header class="admin-topbar">
                <ElButton
                    class="desktop-collapse"
                    :aria-label="collapsed ? '展开导航' : '折叠导航'"
                    :aria-expanded="!collapsed"
                    @click="collapsed = !collapsed"
                    >☰</ElButton
                ><ElButton
                    ref="menuButton"
                    class="mobile-menu"
                    aria-label="打开管理菜单"
                    :aria-expanded="drawer"
                    @click="drawer = true"
                    >☰</ElButton
                >
                <nav class="admin-breadcrumb" aria-label="面包屑">
                    <RouterLink to="/admin/dashboard">管理后台</RouterLink
                    ><span>/</span><span>{{ route.meta.title }}</span>
                </nav>
                <ElDropdown trigger="click" @command="command"
                    ><button
                        class="account-menu"
                        type="button"
                        :aria-label="`管理员 ${auth.user?.username || ''}，打开账号菜单`"
                    >
                        <span class="account-avatar">{{
                            auth.user?.username?.slice(0, 1).toUpperCase()
                        }}</span
                        ><span>{{ auth.user?.username }}</span
                        ><span aria-hidden="true">⌄</span></button
                    ><template #dropdown
                        ><ElDropdownMenu
                            ><ElDropdownItem command="community"
                                >学生社区</ElDropdownItem
                            ><ElDropdownItem
                                command="logout"
                                :disabled="auth.loggingOut"
                                >退出登录</ElDropdownItem
                            ></ElDropdownMenu
                        ></template
                    ></ElDropdown
                >
            </header>
            <main class="admin-main">
                <ElAlert
                    v-if="sessionError"
                    :title="sessionError"
                    type="error"
                    :closable="false"
                    role="alert"
                /><RouterView />
            </main>
        </div>
        <ElDrawer
            v-model="drawer"
            title="社区管理"
            direction="ltr"
            size="min(280px, 86vw)"
            @closed="restoreFocus"
            ><nav class="admin-mobile-nav" aria-label="移动后台导航">
                <RouterLink
                    v-for="item in menus"
                    :key="item.path"
                    :to="item.path"
                    >{{ item.name }}</RouterLink
                >
            </nav></ElDrawer
        >
    </div>
</template>
