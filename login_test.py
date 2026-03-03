import requests
import json

url = 'http://localhost:8080/api/auth/login'
payload = {
    'email': 'ethan.yan@example.com',
    'password': 'TestPassword123'
}

try:
    response = requests.post(url, json=payload)
    result = response.json()
    
    if 'token' in result:
        print('✓ Login successful!')
        print(f"Email: {result['email']}")
        print(f"Token: {result['token'][:30]}...")
    else:
        print(f"❌ Login failed: {result.get('error', 'Unknown error')}")
except Exception as e:
    print(f'Error: {e}')
