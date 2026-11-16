import axios from 'axios'

export const http = axios.create({baseURL: '/api', timeout: 600000})
http.interceptors.response.use(r => r, error => Promise.reject(error.response?.data?.error || error.message || '请求失败'))
