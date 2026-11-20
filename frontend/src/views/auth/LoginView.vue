<script setup>
import { computed, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput } from 'element-plus';
import 'element-plus/theme-chalk/el-alert.css';
import 'element-plus/theme-chalk/el-button.css';
import 'element-plus/theme-chalk/el-form.css';
import 'element-plus/theme-chalk/el-form-item.css';
import 'element-plus/theme-chalk/el-input.css';
import { useAuthStore } from '../../stores/auth.js';
import PasswordInput from '../../components/PasswordInput.vue';
import { safeReturnPath } from '../../router/index.js';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const form = ref();
const credentials = reactive({ username: '', password: '' });
const submitting = ref(false);
const errorMessage = ref('');
const notice = computed(() => auth.initializationError || (route.query.reason === 'expired'
    ? '登录状态已失效，请重新登录' : route.query.reason === 'forbidden' ? '当前账号无权访问该页面' : ''));
const rules = {
    username: [{ required: true, whitespace: true, message: '请输入用户名', trigger: 'blur' },
        { max: 64, message: '用户名最多64个字符', trigger: 'blur' }],
    password: [{ required: true, whitespace: true, message: '请输入密码', trigger: 'blur' },
        { validator: (_rule, value, callback) => {
            callback(new TextEncoder().encode(value).length > 72 ? new Error('密码最多72个UTF-8字节') : undefined);
        }, trigger: 'blur' }],
};

async function submit() {
    if (submitting.value || auth.loggingIn) return;
    submitting.value = true;
    errorMessage.value = '';
    try {
        const valid = await form.value.validate().catch(() => false);
        if (!valid) return;
        await auth.login(credentials.username.trim(), credentials.password);
        credentials.password = '';
        await router.replace(safeReturnPath(router, route.query.redirect, auth.user.role));
    } catch (error) {
        errorMessage.value = error.message;
    } finally {
        submitting.value = false;
    }
}
</script>

<template>
    <div class="auth-card login-card">
        <p class="eyebrow">学生与管理员</p>
        <h2>账号登录</h2>
        <p class="auth-subtitle">使用已有账号访问社区与学习功能。</p>
        <ElAlert v-if="route.query.registered === '1'" class="auth-alert" title="注册成功，请使用新账号登录" type="success" :closable="false" show-icon role="status" />
        <ElAlert
            v-if="errorMessage || notice"
            class="auth-alert"
            :title="errorMessage || notice"
            :type="errorMessage ? 'error' : 'info'"
            :closable="false"
            show-icon
            role="alert"
        />
        <ElForm
            ref="form"
            :model="credentials"
            :rules="rules"
            label-position="top"
            size="large"
            hide-required-asterisk
            @submit.prevent="submit"
        >
            <ElFormItem label="用户名" prop="username" for="username">
                <ElInput
                    id="username"
                    v-model="credentials.username"
                    name="username"
                    placeholder="请输入用户名"
                    autocomplete="username"
                    :maxlength="64"
                    :disabled="submitting"
                />
            </ElFormItem>
            <ElFormItem label="密码" prop="password" for="password">
                <PasswordInput id="password" v-model="credentials.password" :disabled="submitting" />
            </ElFormItem>
            <ElButton
                class="login-button"
                type="primary"
                native-type="submit"
                :loading="submitting"
                :disabled="submitting"
            >
                {{ submitting ? '正在登录' : '登录' }}
                <span v-if="!submitting" aria-hidden="true">→</span>
            </ElButton>
        </ElForm>
        <p class="auth-switch">还没有账号？<RouterLink :to="{ name: 'register', query: { redirect: route.query.redirect } }">立即注册</RouterLink></p>
        <p class="account-help">如需账号帮助，请联系学校管理员。</p>
    </div>
</template>

