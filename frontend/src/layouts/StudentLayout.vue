<script setup>
import { useRoute, useRouter } from 'vue-router';
import {
    ElAlert,
    ElDropdown,
    ElDropdownItem,
    ElDropdownMenu,
} from 'element-plus';
import 'element-plus/es/components/dropdown/style/css.mjs';
import 'element-plus/es/components/dialog/style/css.mjs';
import 'element-plus/es/components/select/style/css.mjs';
import 'element-plus/es/components/table/style/css.mjs';
import 'element-plus/es/components/pagination/style/css.mjs';
import '../assets/styles/community.css';
import { useSessionLifecycle } from '../composables/useSessionLifecycle.js';
const { auth, sessionError, logout } = useSessionLifecycle();
const route = useRoute();
const router = useRouter();
function command(value) {
    if (value === 'logout') logout();
    else router.push(value);
}
</script>
<template>
    <div
        :class="['student-page', { 'agent-page': route.name === 'agent-chat' }]"
    >
        <header class="student-header">
            <div class="header-content">
                <RouterLink class="brand" to="/community"
                    ><span class="brand-mark" aria-hidden="true">知</span
                    >知识问答</RouterLink
                >
                <nav class="student-nav" aria-label="学生导航">
                    <RouterLink to="/community">学生社区</RouterLink
                    ><RouterLink
                        to="/agent/chat"
                        >AI 智能问答</RouterLink
                    ><RouterLink to="/profile">个人中心</RouterLink
                    ><RouterLink
                        v-if="auth.user?.role === 'ADMIN'"
                        to="/admin/dashboard"
                        >社区管理</RouterLink
                    >
                </nav>
                <ElDropdown trigger="click" @command="command"
                    ><button
                        class="student-account"
                        type="button"
                        :aria-label="`${auth.user?.username || ''}，打开账号菜单`"
                    >
                        <span class="user-avatar">{{
                            auth.user?.username?.slice(0, 1).toUpperCase()
                        }}</span
                        ><span class="username"
                            >{{ auth.user?.username
                            }}<small>{{
                                auth.user?.role === 'ADMIN' ? '管理员' : '学生'
                            }}</small></span
                        ><span aria-hidden="true">⌄</span></button
                    ><template #dropdown
                        ><ElDropdownMenu
                            ><ElDropdownItem command="/profile"
                                >个人中心</ElDropdownItem
                            ><ElDropdownItem
                                v-if="auth.user?.role === 'ADMIN'"
                                command="/admin/dashboard"
                                >管理后台</ElDropdownItem
                            ><ElDropdownItem
                                command="logout"
                                :disabled="auth.loggingOut"
                                >退出登录</ElDropdownItem
                            ></ElDropdownMenu
                        ></template
                    ></ElDropdown
                >
            </div>
        </header>
        <main class="student-main">
            <ElAlert
                v-if="sessionError"
                :title="sessionError"
                type="error"
                :closable="false"
                show-icon
                role="alert"
            /><RouterView />
        </main>
    </div>
</template>
<style scoped>
.student-header {
    background: var(--color-surface);
    border-bottom: 1px solid var(--color-border);
}
.header-content {
    max-width: 1440px;
    min-height: 80px;
    margin: auto;
    padding: 14px 32px;
    display: flex;
    align-items: center;
    gap: 40px;
}
.student-nav {
    display: flex;
    align-items: center;
    gap: 28px;
    flex: 1;
    flex-wrap: wrap;
    font-size: 14px;
}
.student-nav a {
    min-height: 44px;
    display: inline-flex;
    align-items: center;
    color: var(--color-muted);
    border-bottom: 2px solid transparent;
}
.student-nav a.router-link-active {
    color: var(--color-primary);
    border-bottom-color: var(--color-primary);
    font-weight: 600;
}
.student-account {
    display: flex;
    align-items: center;
    gap: 12px;
    border: 0;
    background: transparent;
    min-height: 44px;
    color: var(--color-text);
    cursor: pointer;
}
.user-avatar {
    width: 38px;
    height: 38px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: var(--el-color-primary-light-9);
    color: var(--color-primary);
    font-weight: 700;
}
.username {
    font-size: 14px;
    text-align: left;
    max-width: 180px;
    overflow-wrap: anywhere;
}
.username small {
    display: block;
    color: var(--color-muted);
    font-size: 12px;
}
.student-main {
    max-width: var(--community-max);
    padding: 40px 32px 64px;
    margin: auto;
}
.student-main > .el-alert {
    margin-bottom: 24px;
}
.agent-page {
    height: 100dvh;
    display: flex;
    flex-direction: column;
}
.agent-page .student-header {
    flex-shrink: 0;
}
.agent-page .student-main {
    width: 100%;
    max-width: 1488px;
    padding: 20px 24px;
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
}
.agent-page .student-main > section {
    flex: 1;
    min-height: 0;
}
@media (max-width: 1000px) {
    .header-content {
        gap: 20px;
    }
    .student-nav {
        gap: 18px;
    }
    .username {
        max-width: 120px;
    }
}
@media (max-width: 768px) {
    .header-content {
        flex-wrap: wrap;
        gap: 8px 16px;
        padding: 12px 20px;
        justify-content: space-between;
    }
    .student-nav {
        order: 3;
        flex-basis: 100%;
        justify-content: space-between;
        gap: 8px;
    }
    .student-main {
        padding: 28px 20px 48px;
    }
    .username {
        max-width: 130px;
    }
    .brand {
        font-size: 18px;
    }
    .agent-page .student-main {
        padding: 0;
    }
    .agent-page .header-content {
        padding: 8px 16px;
    }
    .agent-page .username {
        display: none;
    }
}
</style>
