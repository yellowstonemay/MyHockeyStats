import requests

# First, login to get a token
login_url = 'http://localhost:8080/api/auth/login'
login_payload = {
    'email': 'ethan.yan@example.com',
    'password': 'TestPassword123'
}

try:
    # Step 1: Login
    print("📝 Logging in...")
    login_response = requests.post(login_url, json=login_payload)
    
    if login_response.status_code != 200:
        print(f"❌ Login failed: {login_response.status_code}")
        print(login_response.text)
        exit(1)
    
    token = login_response.json()['token']
    print(f"✓ Token obtained: {token[:30]}...\n")
    
    # Step 2: Create season with auth header
    print("🏒 Creating season...")
    create_url = 'http://localhost:8080/api/seasons'
    create_payload = {
        'playerProfileId': 2,
        'yearStart': 2024,
        'yearEnd': 2025,
        'teamName': 'New Jersey Rockets AAA',
        'clubName': 'AYHL'
    }
    
    headers = {
        'Authorization': f'Bearer {token}',
        'Content-Type': 'application/json'
    }
    
    create_response = requests.post(create_url, json=create_payload, headers=headers)
    
    print(f"Status Code: {create_response.status_code}")
    
    if create_response.status_code == 200:
        result = create_response.json()
        print("✅ Season created successfully!")
        print(f"  Season ID: {result['id']}")
        print(f"  Team: {result['teamName']}")
        print(f"  Club: {result['clubName']}")
        print(f"  Years: {result['yearStart']}-{result['yearEnd']}")
        print(f"  Games: {result['totalGames']}")
    else:
        print(f"❌ Error: {create_response.status_code}")
        print(f"Response: {create_response.text}")
        
except Exception as e:
    print(f"Error: {e}")
    import traceback
    traceback.print_exc()
