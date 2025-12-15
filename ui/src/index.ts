import { definePlugin } from "@halo-dev/console-shared";
import EncryptBlockExtension from "@/extensions/EncryptBlockExtension";

export default definePlugin({
  extensionPoints: {
    // 注册编辑器扩展
    "default:editor:extension:create": () => {
      return [EncryptBlockExtension];
    },
  },
});
