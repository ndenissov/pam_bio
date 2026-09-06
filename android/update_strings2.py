import os

strings = {
    'values': {
        'permissions_denied': 'Некоторые разрешения отклонены: %1$s',
        'camera_access_denied': 'Нет доступа к камере',
        'grant_permission': 'Предоставить доступ',
        'import_gallery': 'Выбрать из галереи',
        'import_file': 'Импортировать из файла',
        'invalid_file_format': 'Неверный формат ключа'
    },
    'values-en': {
        'permissions_denied': 'Some permissions denied: %1$s',
        'camera_access_denied': 'No camera access',
        'grant_permission': 'Grant permission',
        'import_gallery': 'Choose from gallery',
        'import_file': 'Import from file',
        'invalid_file_format': 'Invalid key format'
    },
    'values-zh': {
        'permissions_denied': '部分权限被拒绝: %1$s',
        'camera_access_denied': '无法访问相机',
        'grant_permission': '授予权限',
        'import_gallery': '从相册选择',
        'import_file': '从文件导入',
        'invalid_file_format': '无效的密钥格式'
    }
}

for folder, new_strings in strings.items():
    path = f'app/src/main/res/{folder}/strings.xml'
    with open(path, 'r') as f:
        content = f.read()
    
    tags = "\n".join([f'    <string name="{k}">{v}</string>' for k, v in new_strings.items()])
    content = content.replace('</resources>', f'{tags}\n</resources>')
    
    with open(path, 'w') as f:
        f.write(content)
