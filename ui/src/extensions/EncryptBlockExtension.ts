import { Extension } from '@tiptap/core'
import { markRaw } from 'vue'
import type { Editor } from '@tiptap/core'
import InsertEncryptBlockToolbarItem from '@/components/InsertEncryptBlockToolbarItem.vue'

export interface ToolbarItem {
    priority: number
    component: any
    props: any
}

export interface EncryptBlockOptions {
    getToolbarItems?: ({
        editor,
    }: {
        editor: Editor;
    }) => ToolbarItem[];
}

/**
 * 加密区块编辑器扩展
 * 在编辑器工具栏添加"🔒插入加密区块"按钮
 */
export default Extension.create<EncryptBlockOptions>({
    name: 'encryptBlock',

    addOptions() {
        return {
            getToolbarItems: ({ editor }) => {
                return [
                    {
                        priority: 150, // 设置优先级
                        component: markRaw(InsertEncryptBlockToolbarItem),
                        props: {
                            editor,
                            isActive: false,
                            disabled: false,
                        },
                    },
                ];
            },
        }
    },
})
