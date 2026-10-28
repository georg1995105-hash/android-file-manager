const {Sha256}=require('../../app/src/main/assets/transfer/app.js');
const crypto=require('crypto');
for(const size of [0,1,55,56,63,64,65,1000,1048577]){
 const data=crypto.randomBytes(size),sha=new Sha256();
 for(let offset=0;offset<size;offset+=31)sha.update(data.subarray(offset,offset+31));
 if(sha.hex()!==crypto.createHash('sha256').update(data).digest('hex'))throw Error('hash mismatch '+size);
}
console.log('PASS: incremental SHA-256, 9 lengths including padding boundaries');
