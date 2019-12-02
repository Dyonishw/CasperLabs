use contract_ffi::{system_contracts::mint::Error, value::U512};

use crate::capabilities::{Addable, Readable, Writable};

pub trait Mint<A, RW>
where
    A: Addable<U512>,
    RW: Readable<U512> + Writable<U512> + Clone,
{
    type PurseId;
    type DepOnlyId;

    fn mint(&self, initial_balance: U512) -> Result<Self::PurseId, Error>;
    fn lookup(&self, p: Self::PurseId) -> Option<RW>;
    fn dep_lookup(&self, p: Self::DepOnlyId) -> Option<A>;

    fn create(&self) -> Self::PurseId {
        self.mint(U512::zero())
            .expect("Creating a zero balance purse should always be allowed.")
    }

    fn transfer(
        &self,
        source: Self::PurseId,
        dest: Self::DepOnlyId,
        amount: U512,
    ) -> Result<(), Error> {
        let source_bal = self.lookup(source).ok_or(Error::SourceNotFound)?;
        let source_value = source_bal.clone().read();
        if amount > source_value {
            return Err(Error::InsufficientFunds);
        }

        let dest_bal = self.dep_lookup(dest).ok_or(Error::DestNotFound)?;
        source_bal.write(&(source_value - amount));
        dest_bal.add(&amount);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use alloc::{collections::BTreeMap, rc::Rc};
    use core::cell::{Cell, RefCell};

    use contract_ffi::value::U512;

    use crate::{
        capabilities::{Addable, Readable, Writable},
        mint::{Error, Mint},
    };

    const GENESIS_PURSE_AMOUNT: u32 = 150;
    const GENESIS_PURSE: FullId = FullId(0);

    type Balance = Rc<Cell<U512>>;

    impl Readable<U512> for Balance {
        fn read(self) -> U512 {
            self.get()
        }
    }

    impl Writable<U512> for Balance {
        fn write(self, value: &U512) {
            self.set(*value);
        }
    }

    impl Addable<U512> for Balance {
        fn add(self, value: &U512) {
            self.update(|x| x + *value);
        }
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    struct FullId(u32);

    impl FullId {
        fn to_dep(self) -> DepId {
            DepId(self.0)
        }
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    struct DepId(u32);

    struct SimpleMint(RefCell<BTreeMap<u32, Balance>>, Cell<u32>);

    impl SimpleMint {
        pub fn new() -> Self {
            let initial = {
                let mut ret = BTreeMap::new();
                ret.insert(
                    GENESIS_PURSE.0,
                    Rc::new(Cell::new(U512::from(GENESIS_PURSE_AMOUNT))),
                );
                ret
            };
            SimpleMint(RefCell::new(initial), Cell::new(1))
        }

        pub fn balance(&self, id: u32) -> Option<U512> {
            self.0.borrow().get(&id).map(|r| Cell::get(r))
        }
    }

    impl Mint<Balance, Balance> for SimpleMint {
        type PurseId = FullId;
        type DepOnlyId = DepId;

        fn mint(&self, balance: U512) -> Result<Self::PurseId, Error> {
            let id = self.1.get();
            self.1.set(id + 1);

            let balance = Rc::new(Cell::new(balance));
            self.0.borrow_mut().insert(id, balance);
            Ok(FullId(id))
        }

        fn lookup(&self, p: Self::PurseId) -> Option<Balance> {
            self.0.borrow().get(&p.0).map(Rc::clone)
        }

        fn dep_lookup(&self, p: Self::DepOnlyId) -> Option<Balance> {
            self.0.borrow().get(&p.0).map(Rc::clone)
        }
    }

    #[test]
    fn transfer_success() {
        let mint = SimpleMint::new();
        let balance1 = U512::from(GENESIS_PURSE_AMOUNT);
        let balance2 = U512::from(0);
        let transfer_amount = U512::from(75);

        let purse1 = GENESIS_PURSE;
        let purse2 = mint.create().to_dep();

        mint.transfer(purse1, purse2, transfer_amount)
            .expect("transfer errored when it should not.");

        let b1 = mint.balance(purse1.0).unwrap();
        let b2 = mint.balance(purse2.0).unwrap();

        assert_eq!(balance1 - transfer_amount, b1);
        assert_eq!(balance2 + transfer_amount, b2);
    }

    #[test]
    fn transfer_overdraft() {
        let mint = SimpleMint::new();
        let balance1 = U512::from(GENESIS_PURSE_AMOUNT);
        let balance2 = U512::from(0);
        let transfer_amount = U512::from(1000);

        let purse1 = GENESIS_PURSE;
        let purse2 = mint.create().to_dep();

        assert_eq!(
            Err(Error::InsufficientFunds),
            mint.transfer(purse1, purse2, transfer_amount)
        );

        let b1 = mint.balance(purse1.0).unwrap();
        let b2 = mint.balance(purse2.0).unwrap();

        // balances remain unchanged
        assert_eq!(balance1, b1);
        assert_eq!(balance2, b2);
    }

    #[test]
    fn transfer_dest_not_exist() {
        let mint = SimpleMint::new();
        let balance1 = U512::from(GENESIS_PURSE_AMOUNT);
        let transfer_amount = U512::from(75);

        let purse1 = GENESIS_PURSE;
        let purse2 = DepId(purse1.0 + 1);

        assert_eq!(
            Err(Error::DestNotFound),
            mint.transfer(purse1, purse2, transfer_amount)
        );

        let b1 = mint.balance(purse1.0).unwrap();
        // balance remains unchanged
        assert_eq!(balance1, b1);
    }

    #[test]
    fn transfer_source_not_exist() {
        let mint = SimpleMint::new();
        let balance1 = U512::from(GENESIS_PURSE_AMOUNT);
        let transfer_amount = U512::from(75);

        let purse1 = GENESIS_PURSE;
        let purse2 = FullId(purse1.0 + 1);

        assert_eq!(
            Err(Error::SourceNotFound),
            mint.transfer(purse2, purse1.to_dep(), transfer_amount)
        );

        let b1 = mint.balance(purse1.0).unwrap();
        // balance remains unchanged
        assert_eq!(balance1, b1);
    }
}
