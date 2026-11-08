import {createRouter, createWebHistory} from 'vue-router'
import DashboardView from './views/DashboardView.vue'
import SingleChatView from './views/SingleChatView.vue'
import MultiTurnChatView from './views/MultiTurnChatView.vue'
import FileSearchView from './views/FileSearchView.vue'
import ImportDocumentView from './views/ImportDocumentView.vue'
import DocumentManagementView from './views/DocumentManagementView.vue'
import DocumentReportsView from './views/DocumentReportsView.vue'
import WebPageView from './views/WebPageView.vue'

export const router = createRouter({
    history: createWebHistory(), routes: [
        {path: '/', redirect: '/dashboard'}, {path: '/dashboard', component: DashboardView, meta: {title: '仪表盘'}},
        {path: '/chat', component: SingleChatView, meta: {title: '单轮问答'}}, {
            path: '/conversation',
            component: MultiTurnChatView,
            meta: {title: '多轮问答'}
        },
        {path: '/search', component: FileSearchView, meta: {title: '文件检索'}}, {
            path: '/import',
            component: ImportDocumentView,
            meta: {title: '导入文档'}
        },
        {path: '/documents', component: DocumentManagementView, meta: {title: '文档管理'}}, {
            path: '/reports',
            component: DocumentReportsView,
            meta: {title: '总结 / 报告'}
        },
        {path: '/webpage', component: WebPageView, meta: {title: '获取外部网页'}}
    ]
})
