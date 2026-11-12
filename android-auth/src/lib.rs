use horizon_launcher::account;
use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::json;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};

static DEVICE_CODE: OnceLock<Mutex<Option<account::DeviceCode>>> = OnceLock::new();
static CANCELLED: AtomicBool = AtomicBool::new(false);

fn pending() -> &'static Mutex<Option<account::DeviceCode>> {
    DEVICE_CODE.get_or_init(|| Mutex::new(None))
}

fn jstr(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn reply(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(value) => jstr(env, value),
        Err(error) => jstr(env, json!({"error": error}).to_string()),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_auth_MicrosoftAuth_nativeStart(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let result = (|| {
        let client_id = account::client_id().ok_or_else(|| account::NOT_CONFIGURED.to_string())?;
        let code = account::device_code(&account::Endpoints::live(), &client_id)?;
        CANCELLED.store(false, Ordering::Release);
        let response = json!({
            "userCode": code.user_code,
            "verificationUri": code.verification_uri,
            "expiresAt": code.expires_at,
            "interval": code.interval,
        });
        *pending().lock().map_err(|_| "Could not lock Microsoft sign-in state".to_string())? = Some(code);
        Ok(response.to_string())
    })();
    reply(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_auth_MicrosoftAuth_nativeComplete(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let result = (|| {
        let code = pending()
            .lock()
            .map_err(|_| "Could not lock Microsoft sign-in state".to_string())?
            .take()
            .ok_or_else(|| "No Microsoft sign-in is waiting. Start sign-in again.".to_string())?;
        let client_id = account::client_id().ok_or_else(|| account::NOT_CONFIGURED.to_string())?;
        let ep = account::Endpoints::live();
        let ms = account::wait_for_sign_in(&ep, &client_id, &code, &|| CANCELLED.load(Ordering::Acquire))?;
        let (signed_in, secrets) = account::finish_sign_in(&ep, ms)?;
        let name = signed_in.name.clone();
        let uuid = signed_in.uuid.clone();
        let mut accounts = account::Accounts::load();
        accounts.add(account::store(), signed_in, &secrets)?;
        accounts.save()?;
        CANCELLED.store(false, Ordering::Release);
        Ok(json!({"name": name, "uuid": uuid}).to_string())
    })();
    reply(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_auth_MicrosoftAuth_nativeCancel(
    _env: JNIEnv,
    _this: JObject,
) -> jstring {
    CANCELLED.store(true, Ordering::Release);
    std::ptr::null_mut()
}
