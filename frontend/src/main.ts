import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
// tokens 必须最先加载：它是所有颜色的唯一来源，后面的样式表只引用变量。
import './styles/tokens.css'
import './styles.css'
import './styles/workbench.css'
import { bootstrapTheme } from './theme'

// 在挂载前写入 <html data-theme>，避免首帧闪一下错误的主题。
bootstrapTheme()

createApp(App).use(router).mount('#app')
