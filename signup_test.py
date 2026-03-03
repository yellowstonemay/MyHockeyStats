import requests
import json

url = 'http://localhost:8080/api/auth/signup'
payload = {
    'email': 'ethan.yan@example.com',
    'password': 'TestPassword123',
    'fullName': 'Ethan Yan'
}

try:
    response = requests.post(url, json=payload)
    result = response.json()
    
    if 'token' in result:
        print('✓ Account created successfully')
        print(f"Email: {result['email']}")
        print(f"Token: {result['token'][:20]}...")
    else:
        print(f"Error: {result.get('error', 'Unknown error')}")
except Exception as e:
    print(f'Error: {e}')
