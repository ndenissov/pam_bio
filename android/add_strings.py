import os

strings = {
    'values': {
        'sudo_request': 'Запрос sudo',
        'screen_unlock': 'Разблокировка экрана',
        'auth_default': 'Аутентификация',
        'auth_default_param': 'Аутентификация (%1$s)',
        'user_label': 'Пользователь: %1$s',
        'service_label': 'Сервис: %1$s',
        'auth_approve': 'Подтвердить биометрией',
        'auth_deny': 'Отклонить',
        'scan_qr_title': 'Сканировать QR',
        'point_camera': 'Наведите камеру на QR-код',
        'qr_found': 'QR найден: %1$s',
        'generating_keys': 'Генерация ключей…',
        'searching_network': 'Поиск %1$s в сети…',
        'connecting_to': 'Подключение к %1$s:%2$s…',
        'pairing': 'Сопряжение…',
        'not_found_network': 'Не удалось найти %1$s в сети',
        'failed_connect': 'Не удалось подключиться',
        'pairing_rejected': 'Сопряжение отклонено ПК',
        'error_prefix': 'Ошибка: %1$s'
    },
    'values-en': {
        'sudo_request': 'Sudo Request',
        'screen_unlock': 'Screen Unlock',
        'auth_default': 'Authentication',
        'auth_default_param': 'Authentication (%1$s)',
        'user_label': 'User: %1$s',
        'service_label': 'Service: %1$s',
        'auth_approve': 'Approve with Biometrics',
        'auth_deny': 'Deny',
        'scan_qr_title': 'Scan QR',
        'point_camera': 'Point camera at QR code',
        'qr_found': 'QR found: %1$s',
        'generating_keys': 'Generating keys…',
        'searching_network': 'Searching for %1$s on network…',
        'connecting_to': 'Connecting to %1$s:%2$s…',
        'pairing': 'Pairing…',
        'not_found_network': 'Could not find %1$s on network',
        'failed_connect': 'Failed to connect',
        'pairing_rejected': 'Pairing rejected by PC',
        'error_prefix': 'Error: %1$s'
    },
    'values-zh': {
        'sudo_request': 'Sudo 请求',
        'screen_unlock': '解锁屏幕',
        'auth_default': '身份验证',
        'auth_default_param': '身份验证 (%1$s)',
        'user_label': '用户: %1$s',
        'service_label': '服务: %1$s',
        'auth_approve': '使用生物识别批准',
        'auth_deny': '拒绝',
        'scan_qr_title': '扫描 QR 码',
        'point_camera': '将相机对准 QR 码',
        'qr_found': '已找到 QR: %1$s',
        'generating_keys': '正在生成密钥…',
        'searching_network': '正在网络中搜索 %1$s…',
        'connecting_to': '正在连接至 %1$s:%2$s…',
        'pairing': '配对中…',
        'not_found_network': '在网络中找不到 %1$s',
        'failed_connect': '连接失败',
        'pairing_rejected': '配对被电脑拒绝',
        'error_prefix': '错误: %1$s'
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
