import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import ElementPlusX from 'vue-element-plus-x'
import App from './App.vue'
import { createPinia } from 'pinia'
import router from './router'

const app = createApp(App)
const pinia = createPinia()

app.use(ElementPlus)
app.use(ElementPlusX)
app.use(pinia)
app.use(router)
app.mount('#app')