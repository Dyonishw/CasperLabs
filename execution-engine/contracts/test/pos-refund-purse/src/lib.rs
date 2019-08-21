#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
extern crate cl_std;

use alloc::vec::Vec;

use cl_std::contract_api;
use cl_std::contract_api::pointers::{ContractPointer, UPointer};
use cl_std::key::Key;
use cl_std::uref::AccessRights;
use cl_std::value::account::PurseId;

enum Error {
    GetPosOuterURef = 1,
    GetPosInnerURef = 2,
    RefundPurseShouldNotExist = 3,
    RefundPurseNotFound = 4,
    RefundPurseInvalid = 5,
    RefundPurseIncorrectAccessRights = 6,
}

fn purse_to_key(p: &PurseId) -> Key {
    Key::URef(p.value())
}

fn set_refund_purse(pos: &ContractPointer, p: &PurseId) {
    contract_api::call_contract::<_, ()>(
        pos.clone(),
        &("set_refund_purse", *p),
        &vec![purse_to_key(p)],
    );
}

fn get_refund_purse(pos: &ContractPointer) -> Option<PurseId> {
    contract_api::call_contract(pos.clone(), &"get_refund_purse", &Vec::new())
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = {
        let outer: UPointer<Key> = contract_api::get_uref("pos")
            .and_then(Key::to_u_ptr)
            .unwrap_or_else(|| contract_api::revert(Error::GetPosInnerURef as u32));
        if let Some(ContractPointer::URef(inner)) = contract_api::read::<Key>(outer).to_c_ptr() {
            ContractPointer::URef(UPointer::new(inner.0, AccessRights::READ))
        } else {
            contract_api::revert(Error::GetPosOuterURef as u32);
        }
    };

    let p1 = contract_api::create_purse();
    let p2 = contract_api::create_purse();

    // get_refund_purse should return None before setting it
    let refund_result = get_refund_purse(&pos_pointer);
    if refund_result.is_some() {
        contract_api::revert(Error::RefundPurseShouldNotExist as u32);
    }

    // it should return Some(x) after calling set_refund_purse(x)
    set_refund_purse(&pos_pointer, &p1);
    let refund_purse = match get_refund_purse(&pos_pointer) {
        None => contract_api::revert(Error::RefundPurseNotFound as u32),
        Some(x) if x.value().addr() == p1.value().addr() => x.value(),
        Some(_) => contract_api::revert(Error::RefundPurseInvalid as u32),
    };

    // the returned purse should not have any access rights
    if refund_purse.is_addable() || refund_purse.is_writeable() || refund_purse.is_readable() {
        contract_api::revert(Error::RefundPurseIncorrectAccessRights as u32)
    }

    // get_refund_purse should return correct value after setting a second time
    set_refund_purse(&pos_pointer, &p2);
    match get_refund_purse(&pos_pointer) {
        None => contract_api::revert(Error::RefundPurseNotFound as u32),
        Some(x) if x.value().addr() == p2.value().addr() => (),
        Some(_) => contract_api::revert(Error::RefundPurseInvalid as u32),
    }
}