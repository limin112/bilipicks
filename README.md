# BiliPicks

家长手选的 B 站视频播放页。没有推荐算法，只播清单里的视频。

**Demo：<https://minlibuilds.com/bili/>**

- **成长路线**：闯关式的学习路线（示例数据是「从零到深度学习」9 关 49 步），每关配一个动手任务。看完一条点「✓ 看完了」，进度存在本机浏览器里。
- **一屏一条的播放页**：手机上往上滑是下一条，电脑用滚轮或 <kbd>J</kbd> <kbd>K</kbd>；滑到哪条自动播哪条（顶上「自动播放」可关）。只在清单里滑，不会连播到清单外的视频。
- **频道榜**：给 B 站频道分 S～D 档，并标出孩子现在能不能看。
- **分类浏览 + B 站官方嵌入播放器**：没有热度流。
- 大屏模式（接电视、投屏）、深浅色、键盘快捷键，手机、平板、电脑都能用。

整个网站就是三个文件，不需要构建，也不需要服务器：

| 文件 | 作用 |
|---|---|
| `index.html` | 页面、样式和交互逻辑 |
| `data.js` | 所有内容：视频、成长路线、频道榜、策展人信息 |
| `update.py` | 维护脚本：检查失效视频、补封面和时长、添加新视频（只用 Python 标准库） |

## 做一个你自己的版本

1. Fork 或下载这个仓库。
2. 直接双击打开 `index.html` 就能看（不需要本地服务器）。
3. 改 `data.js` 里的内容，见下面「数据格式」。
4. 部署到任意静态托管：GitHub Pages、Cloudflare Pages、Netlify 都可以，根目录就是网站。

### 改策展人信息

`data.js` 开头的 `site`：

```json
"site": {
  "curator": "你的名字",
  "curatorHandle": "@你的账号",
  "curatorUrl": "https://…",
  "audience": "三四年级",
  "timezone": "Asia/Shanghai"
}
```

About 页里「为什么做」「使用前请知道」「没放进来的」几段文字写在 `index.html` 里，按你的情况改。

### 加视频

```bash
python3 update.py add BV1xxxxxxxxx --cat 数学 --age 10 --reason "为什么推荐" \
    [--caution "家长提示"] [--page 2] [--stage 3 --note "在成长路线里的作用"] \
    [--title "自己起的标题"] [--orig "原作者"]
```

标题、UP 主、封面、时长会自动从 B 站读取。`--stage` 会同时把它加进成长路线的第几关（从 1 开始）。`--title` 用自己写的标题代替 B 站标题（刷新时不再提醒标题变了）；`--orig` 用在搬运、译制的视频上，页面会标「搬运」并写明原作者。

### 定期检查

```bash
python3 update.py            # 检查失效视频、刷新封面/时长/粉丝数，写回 data.js
python3 update.py --check    # 只出报告，不改文件
python3 update.py --deploy   # 刷新后 git commit + push
```

失效的视频会被标记为 `dead`，页面自动隐藏；恢复了会自动重新显示。标题变化只提示、不覆盖，因为标题可能是你手动改短过的。

## 数据格式

`data.js` 里是一行 `const DATA = {...};`（必须保持单行，`update.py` 靠这一行读写）。手改时建议先把 JSON 部分拷到编辑器里格式化，改完再压回一行；或者直接用 `update.py add`。

| 字段 | 说明 |
|---|---|
| `site` | 策展人信息，见上 |
| `categories` | 分类名的数组，决定首页分类顺序 |
| `videos[]` | `id`（BV 号）、`title`、`url`、`channel`、`channelUrl`、`category`、`minAge`、`reason`（推荐理由）、`caution`（家长提示）、`cover`、`duration`；可选 `page`（默认播第几 P）、`parts`（合集集数）、`orig`（搬运视频的原作者）、`titleLocked`（标题是自己写的）、`dead` |
| `channels` | UP 主名 → 主页链接 |
| `path` | 成长路线：`title`、`intro`、`tips[]`、`stages[]`（每一关有 `icon`、`name`、`age`、`goal`、`items[]`，可选 `mission`；`items` 里是 `{id, note}`，`id` 必须是 `videos` 里有的 BV 号；`mission` 是动手任务 `{text, url, label}`） |
| `tiers[]` | 频道榜：每档 `tier`（S/A/B/C/D）、`label` 和 `items[]`（`name`、`mid`、`what`、`like`、`kid`、`note` 等） |
| `originals[]` | 海外原版频道的官方 B 站号 |
| `built` | 最后更新日期，`update.py` 自动写 |

`kid` 的取值：`go`（现在就能看）、`interest`（看孩子兴趣）、`pick`（家长先挑）、`later`（初中以后）、`unknown`。

不想要成长路线或频道榜，把 `path` 或 `tiers` 删掉即可，对应的导航会显示为空。

## 关于 B 站播放

- 用的是 B 站官方嵌入播放器（`player.bilibili.com`），不抓取视频流、不绕过登录或会员限制。
- B 站限制嵌入播放器最高约 480P。想看 1080P，点「去 B 站看高清」到 B 站播放（需要登录）。
- 电脑上滑到就会带声音自动播放；手机和平板上 B 站用的是手机版播放器，它不会自己开播，要点一下画面。
- 封面和视频信息来自 B 站公开接口，`update.py` 每个请求间隔约半秒，不登录。

本项目不是 B 站官方产品。视频版权归各 UP 主所有。

## 许可

MIT，见 [LICENSE](LICENSE)。清单内容（推荐理由、路线编排、频道分档）欢迎参考和改写，注明来源更好。
