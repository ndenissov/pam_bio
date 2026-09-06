import os
import re

strings = {
    'values': {
        'revoke_camera_title': 'Права на камеру',
        'revoke_camera_text': 'ПК успешно добавлен! Если вы не планируете подключать другие ПК в ближайшее время, для внутреннего спокойствия вы можете отобрать у приложения права на камеру.',
        'revoke_now': 'Отозвать сейчас',
        'later': 'Позже',
        'revoke_kill_msg': 'Права будут отозваны при следующем закрытии приложения'
    },
    'values-en': {
        'revoke_camera_title': 'Camera Permission',
        'revoke_camera_text': 'PC added successfully! If you don\'t plan to add more PCs soon, you can revoke camera permissions for peace of mind.',
        'revoke_now': 'Revoke Now',
        'later': 'Later',
        'revoke_kill_msg': 'Permissions will be revoked when the app is next closed'
    },
    'values-zh': {
        'revoke_camera_title': '相机权限',
        'revoke_camera_text': '电脑添加成功！如果您近期不打算添加其他电脑，可以撤销相机权限以求安心。',
        'revoke_now': '立即撤销',
        'later': '稍后',
        'revoke_kill_msg': '权限将在应用下次关闭时撤销'
    }
}

for folder, new_strings in strings.items():
    path = f'app/src/main/res/{folder}/strings.xml'
    with open(path, 'r') as f:
        content = f.read()
    
    # Change PamBio to PAM Bio
    content = content.replace('>PamBio<', '>PAM Bio<')
    
    # Add new strings before </resources>
    tags = "\n".join([f'    <string name="{k}">{v}</string>' for k, v in new_strings.items()])
    content = content.replace('</resources>', f'{tags}\n</resources>')
    
    with open(path, 'w') as f:
        f.write(content)
