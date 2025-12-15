import { definePlugin } from "@halo-dev/console-shared";
import EncryptBlockExtension from "@/extensions/EncryptBlockExtension";
import TotpDisplay from "@/components/TotpDisplay.vue";
import { markRaw } from "vue";
import { IconLockPasswordLine } from "@halo-dev/components";

export default definePlugin({
  extensionPoints: {
    // 注册编辑器扩展
    "default:editor:extension:create": () => {
      return [EncryptBlockExtension];
    },
  },
  routes: [
    {
      parentName: "ToolsRoot",
      route: {
        path: "totp",
        name: "TotpManager",
        component: TotpDisplay,
        meta: {
          title: "动态密码",
          searchable: true,
          permissions: ["*"],
          menu: {
            name: "动态密码",
            icon: markRaw(IconLockPasswordLine),
            priority: 10,
          },
        },
      },
    },
  ],
});
