import asyncio

from grpclib.client import Channel

from . import casper_pb2 as casper
from . import casper_grpc
from . import info_pb2 as info

from casperlabs_client.utils import (
    key_variant,
    make_deploy,
    sign_deploy,
    get_public_key,
    bundled_contract,
)
from . import abi

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 40401


class ScopedChannel(object):
    def __init__(self, channel):
        self.channel = channel

    def __enter__(self):
        return self.channel

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.channel.close()


class CasperService(object):
    def __init__(self, client):
        self.client = client

    def __getattr__(self, name):
        async def method(*args):
            with ScopedChannel(self.client.channel()) as channel:
                service = casper_grpc.CasperServiceStub(channel)
                return await getattr(service, name)(*args)

        return method


class CasperLabsClientAIO(object):
    """
    gRPC asyncio CasperLabs client.
    """

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT):
        self.host = host
        self.port = port
        self.casper_service = CasperService(self)

    def channel(self):
        return Channel(self.host, self.port)

    async def show_blocks(self, depth=1, max_rank=0, full_view=True):
        return await self.casper_service.StreamBlockInfos(
            casper.StreamBlockInfosRequest(
                depth=depth, max_rank=max_rank, view=self._block_info_view(full_view)
            )
        )

    async def deploy(
        self,
        from_addr: bytes = None,
        gas_price: int = 10,
        payment: str = None,
        session: str = None,
        public_key: str = None,
        private_key: str = None,
        session_args: bytes = None,
        payment_args: bytes = None,
        payment_amount: int = None,
        payment_hash: bytes = None,
        payment_name: str = None,
        payment_uref: bytes = None,
        session_hash: bytes = None,
        session_name: str = None,
        session_uref: bytes = None,
        ttl_millis: int = 0,
        dependencies=None,
        chain_name: str = None,
    ):
        deploy = make_deploy(
            from_addr=from_addr,
            gas_price=gas_price,
            payment=payment,
            session=session,
            public_key=public_key,
            session_args=session_args,
            payment_args=payment_args,
            payment_amount=payment_amount,
            payment_hash=payment_hash,
            payment_name=payment_name,
            payment_uref=payment_uref,
            session_hash=session_hash,
            session_name=session_name,
            session_uref=session_uref,
            ttl_millis=ttl_millis,
            dependencies=dependencies,
            chain_name=chain_name,
        )

        deploy = sign_deploy(
            deploy, get_public_key(public_key, from_addr, private_key), private_key
        )
        await self.send_deploy(deploy)
        return deploy.deploy_hash.hex()

    async def send_deploy(self, deploy):
        return await self.casper_service.Deploy(casper.DeployRequest(deploy=deploy))

    async def wait_for_deploy_processed(self, deploy_hash, on_error_raise=True):
        result = None
        while True:
            result = await self.show_deploy(deploy_hash)
            if result.status.state != 1:  # PENDING
                break
            # result.status.state == PROCESSED (2)
            await asyncio.sleep(0.1)

        if on_error_raise:
            last_processing_result = result.processing_results[0]
            if last_processing_result.is_error:
                raise Exception(
                    f"Deploy {deploy_hash} execution error: {last_processing_result.error_message}"
                )
        return result

    async def show_deploy(self, deploy_hash_base16: str, full_view=True):
        return await self.casper_service.GetDeployInfo(
            casper.GetDeployInfoRequest(
                deploy_hash_base16=deploy_hash_base16,
                view=self._deploy_info_view(full_view),
            )
        )

    async def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        q = casper.StateQuery(key_variant=key_variant(key_type), key_base16=key)
        q.path_segments.extend([name for name in path.split("/") if name])
        return await self.casper_service.GetBlockState(
            casper.GetBlockStateRequest(block_hash_base16=block_hash, query=q)
        )

    async def transfer(self, target_account_hex, amount, **deploy_args):
        deploy_args["session"] = bundled_contract("transfer_to_account.wasm")
        deploy_args["session_args"] = abi.ABI.args(
            [
                abi.ABI.account("account", bytes.fromhex(target_account_hex)),
                abi.ABI.long_value("amount", amount),
            ]
        )
        return await self.deploy(**deploy_args)

    async def balance(self, address: str, block_hash: str):
        value = await self.query_state(block_hash, address, "", "address")
        account = None
        try:
            account = value.account
        except AttributeError:
            return Exception(f"balance: Expected Account type value under {address}.")

        urefs = [u for u in account.named_keys if u.name == "mint"]
        if len(urefs) == 0:
            raise Exception(
                "balance: Account's named_keys map did not contain Mint contract address."
            )

        mint_public = urefs[0]

        mint_public_hex = mint_public.key.uref.uref.hex()
        purse_addr_hex = account.purse_id.uref.hex()
        local_key_value = f"{mint_public_hex}:{purse_addr_hex}"

        balance_uref = await self.query_state(block_hash, local_key_value, "", "local")
        balance = await self.query_state(
            block_hash, balance_uref.key.uref.uref.hex(), "", "uref"
        )
        return int(balance.big_int.value)

    async def show_block(self, block_hash_base16: str, full_view=True):
        return await self.casper_service.GetBlockInfo(
            casper.GetBlockInfoRequest(
                block_hash_base16=block_hash_base16,
                view=self._block_info_view(full_view),
            )
        )

    async def show_deploys(self, block_hash_base16: str, full_view=True):
        return await self.casper_service.StreamBlockDeploys(
            casper.StreamBlockDeploysRequest(
                block_hash_base16=block_hash_base16,
                view=self._deploy_info_view(full_view),
            )
        )

    def _deploy_info_view(self, full_view):
        return full_view and info.DeployInfo.View.FULL or info.DeployInfo.View.BASIC

    def _block_info_view(self, full_view):
        return full_view and info.BlockInfo.View.FULL or info.BlockInfo.View.BASIC
