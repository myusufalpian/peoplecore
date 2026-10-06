import time
import uuid

import boto3


VISIBILITY_TIMEOUT_SECONDS = 1

client = boto3.client(
    "sqs", endpoint_url="http://localhost:4566", region_name="ap-southeast-1",
    aws_access_key_id="test", aws_secret_access_key="test"
)
for name in ("peoplecore-notifications", "peoplecore-notifications-dlq"):
    client.get_queue_url(QueueName=name)

body = "local-queue-verification-" + uuid.uuid4().hex
url = client.create_queue(QueueName="peoplecore-smoke-" + uuid.uuid4().hex)["QueueUrl"]
try:
    sent = client.send_message(QueueUrl=url, MessageBody=body)
    messages = client.receive_message(
        QueueUrl=url, WaitTimeSeconds=5, VisibilityTimeout=VISIBILITY_TIMEOUT_SECONDS
    ).get("Messages", [])
    assert len(messages) == 1, "Expected one message"
    message = messages[0]
    assert message["Body"] == body
    assert message["MessageId"] == sent["MessageId"]
    client.delete_message(QueueUrl=url, ReceiptHandle=message["ReceiptHandle"])
    time.sleep(VISIBILITY_TIMEOUT_SECONDS + 1)
    assert not client.receive_message(QueueUrl=url, WaitTimeSeconds=5).get("Messages"), \
        "Message remains available after deletion and visibility timeout"
    print("PASS: configured queues exist; isolated send/receive/delete succeeds.")
finally:
    client.delete_queue(QueueUrl=url)
