注：
- 该系统没有正式的登陆，只有用户id来保持独立性各用户的独立性，所以需要再用户使用系统前建立独立的用户id，之后的每一次访问就能凭用户id获取到历史的会话记录，并且存入pinia中做状态管理，当用户清除缓存后，凭借之前相同的用户id，依旧能登陆系统
- 该系统的ai组件主要是依赖与element-plus-x，所以需要详细阅读element-plus-x的文档
- 该系统其他组件为element-plus提供
- 接口文档详细请看doc/default_OpenAPI.json，接口说明请阅读doc/frontend-api-doc.md
- 页面设计使用element-plus-x的ai组件，左侧菜单采用Conversations 会话管理组件，右侧为聊天界面采用EditorSender 编辑输入框，XMarkdown 渲染组件，采用流式接口进行渲染后端响应的接口
- 采用ui-ux-pro-max对前端进行整体设计
