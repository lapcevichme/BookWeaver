from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

from api import state

bearer_scheme = HTTPBearer()


async def verify_token(credentials: HTTPAuthorizationCredentials = Depends(bearer_scheme)):
    """
    Проверяет, что токен, переданный в заголовке Authorization: Bearer <token>,
    совпадает с нашим серверным токеном.
    """
    if credentials.credentials != state.SERVER_TOKEN:
        print(f"--- ОШИБКА АУТЕНТИФИКАЦИИ ---")
        print(f"Токен от клиента: {credentials.credentials}")
        print(f"Токен на сервере: {state.SERVER_TOKEN}")
        print(f"---------------------------------")

        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return True