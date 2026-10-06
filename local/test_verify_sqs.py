import runpy
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, call, patch


SCRIPT = Path(__file__).with_name("verify-sqs.py")


class VerifySqsTest(unittest.TestCase):
    def run_verification(self, deleted):
        client = Mock()
        client.create_queue.return_value = {"QueueUrl": "smoke-queue"}
        client.send_message.return_value = {"MessageId": "message-id"}
        message = {
            "Body": "local-queue-verification-fixed-id",
            "MessageId": "message-id",
            "ReceiptHandle": "receipt-handle",
        }
        client.receive_message.side_effect = [
            {"Messages": [message]},
            {"Messages": [] if deleted else [message]},
        ]
        boto3 = SimpleNamespace(client=Mock(return_value=client))
        with patch.dict("sys.modules", {"boto3": boto3}), \
                patch("uuid.uuid4", return_value=SimpleNamespace(hex="fixed-id")), \
                patch("time.sleep") as sleep:
            operations = Mock()
            operations.attach_mock(client.receive_message, "receive")
            operations.attach_mock(client.delete_message, "delete")
            operations.attach_mock(sleep, "sleep")
            if deleted:
                runpy.run_path(str(SCRIPT))
            else:
                with self.assertRaisesRegex(AssertionError, "Message remains available"):
                    runpy.run_path(str(SCRIPT))
            sleep.assert_called_once_with(2)
            self.assertEqual(operations.mock_calls, [
                call.receive(QueueUrl="smoke-queue", WaitTimeSeconds=5, VisibilityTimeout=1),
                call.delete(QueueUrl="smoke-queue", ReceiptHandle="receipt-handle"),
                call.sleep(2),
                call.receive(QueueUrl="smoke-queue", WaitTimeSeconds=5),
            ])
        self.assertEqual(client.receive_message.call_args_list[0].kwargs, {
            "QueueUrl": "smoke-queue", "WaitTimeSeconds": 5, "VisibilityTimeout": 1,
        })
        client.delete_message.assert_called_once_with(
            QueueUrl="smoke-queue", ReceiptHandle="receipt-handle"
        )
        client.delete_queue.assert_called_once_with(QueueUrl="smoke-queue")

    def test_deleted_message_passes(self):
        self.run_verification(deleted=True)

    def test_message_remaining_after_timeout_fails(self):
        self.run_verification(deleted=False)


if __name__ == "__main__":
    unittest.main()
