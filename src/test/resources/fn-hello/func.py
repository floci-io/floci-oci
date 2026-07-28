import io
import json

from fdk import response


def handler(ctx, data: io.BytesIO = None):
    name = "world"
    try:
        body = json.loads(data.getvalue())
        name = body.get("name", name)
    except Exception:
        pass
    return response.Response(
        ctx,
        response_data=json.dumps({"message": "Hello " + name}),
        headers={"Content-Type": "application/json"},
    )
