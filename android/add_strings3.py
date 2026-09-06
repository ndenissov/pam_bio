import os

strings = {
    'values': {
        'import_clipboard': 'Вставить из буфера',
        'invalid_clipboard': 'Буфер обмена пуст или не содержит ключа'
    },
    'values-en': {
        'import_clipboard': 'Paste from clipboard',
        'invalid_clipboard': 'Clipboard is empty or invalid'
    },
    'values-zh': {
        'import_clipboard': '从剪贴板粘贴',
        'invalid_clipboard': '剪贴板为空或无效'
    }
}

for folder, new_strings in strings.items():
    path = f'app/src/main/res/{folder}/strings.xml'
    if not os.path.exists(path): continue
    with open(path, 'r') as f:
        content = f.read()
    
    tags = "\n".join([f'    <string name="{k}">{v}</string>' for k, v in new_strings.items()])
    content = content.replace('</resources>', f'{tags}\n</resources>')
    
    with open(path, 'w') as f:
        f.write(content)
