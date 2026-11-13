import os
import uvicorn

if __name__ == "__main__":
    uvicorn.run("backend.app:app", host=os.environ.get("ROOMSCAN_HOST", "127.0.0.1"), port=int(os.environ.get("ROOMSCAN_PORT", "8765")), access_log=False)
