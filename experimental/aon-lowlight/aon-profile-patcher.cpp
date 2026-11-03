#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>
#include <unistd.h>
#include <vector>

using Bytes = std::vector<uint8_t>;
struct Field { uint32_t tag, wire; size_t begin, keyEnd, data, end; uint64_t value; };
static void require(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }
static uint64_t varint(const Bytes& b, size_t& p) {
    uint64_t v = 0;
    for (unsigned i = 0; i < 10; ++i) {
        require(p < b.size(), "truncated varint");
        const uint8_t c = b[p++];
        require(i != 9 || c <= 1, "varint overflow");
        v |= uint64_t(c & 127) << (7 * i);
        if (!(c & 128)) return v;
    }
    throw std::runtime_error("invalid varint");
}
static std::vector<Field> fields(const Bytes& b) {
    std::vector<Field> out;
    size_t p = 0;
    while (p < b.size()) {
        Field f{}; f.begin = p;
        const auto k = varint(b, p);
        require((k >> 3) > 0 && (k >> 3) <= 0x1fffffff, "invalid protobuf tag");
        f.tag = uint32_t(k >> 3); f.wire = k & 7; f.keyEnd = p;
        if (f.wire == 0) { f.data = p; f.value = varint(b, p); }
        else if (f.wire == 1 || f.wire == 5) {
            f.data = p; const size_t n = f.wire == 1 ? 8 : 4;
            require(n <= b.size() - p, "truncated fixed field"); p += n;
        } else if (f.wire == 2) {
            const auto n = varint(b, p); f.data = p;
            require(n <= b.size() - p, "truncated message"); p += size_t(n);
        } else throw std::runtime_error("unsupported protobuf wire type (groups are not accepted)");
        f.end = p; out.push_back(f);
    }
    return out;
}
static Field one(const Bytes& b, uint32_t tag, uint32_t wire) {
    Field found{}; size_t count = 0;
    for (auto f : fields(b)) if (f.tag == tag) { found = f; ++count; }
    require(count == 1 && found.wire == wire, "required field absent, duplicate, or wrong wire type");
    return found;
}
static Bytes slice(const Bytes& b, size_t a, size_t z) { return Bytes(b.begin() + a, b.begin() + z); }
static Bytes child(const Bytes& b, uint32_t tag) { auto f = one(b, tag, 2); return slice(b, f.data, f.end); }
static double number(const Bytes& b, uint32_t tag) {
    auto f = one(b, tag, 5); uint32_t v = 0;
    for (unsigned i = 0; i < 4; ++i) v |= uint32_t(b[f.data + i]) << (8 * i);
    float value; std::memcpy(&value, &v, 4); require(std::isfinite(value), "nonfinite field"); return value;
}
static void putFloat(Bytes& b, uint32_t tag, double d) {
    auto f = one(b, tag, 5); const float value = static_cast<float>(d);
    require(std::isfinite(value) && static_cast<double>(value) == d, "value must be exactly representable as float32");
    uint32_t v; std::memcpy(&v, &value, 4);
    for (unsigned i = 0; i < 4; ++i) b[f.data + i] = uint8_t(v >> (8 * i));
}
static void append(Bytes& dst, const Bytes& src) { dst.insert(dst.end(), src.begin(), src.end()); }
static void putVarint(Bytes& b, uint64_t v) {
    do { uint8_t c = v & 127; v >>= 7; b.push_back(c | (v ? 128 : 0)); } while (v);
}
static Bytes replaceChild(const Bytes& b, uint32_t tag, const Bytes& replacement) {
    auto f = one(b, tag, 2); Bytes out = slice(b, 0, f.keyEnd);
    putVarint(out, replacement.size()); append(out, replacement); append(out, slice(b, f.end, b.size())); return out;
}
struct Profile { Bytes aec, block, table; std::vector<Bytes> rows; std::vector<Field> rowFields; };
static bool near(double a, double b) { return std::abs(a - b) <= 0.000001; }
static Profile inspect(const Bytes& b) {
    Profile p; p.aec = child(b, 5); p.block = child(p.aec, 5); p.table = child(p.block, 1);
    for (auto f : fields(p.table)) if (f.tag == 2) {
        require(f.wire == 2, "arbitration row wrong wire type");
        p.rowFields.push_back(f); p.rows.push_back(slice(p.table, f.data, f.end));
    }
    require(p.rows.size() == 9 || p.rows.size() == 10, "expected 9 original rows or 10 candidate rows");
    const double gains[] = {1,1,1,3,6,8,8,8,16};
    const double ets[] = {100000,1000000,4000000,10000000,10000000,20000000,40000000,200000000,200000000};
    const uint64_t modes[] = {2,2,2,1,2,1,1,1,2};
    const double indices[] = {60.61669921875,134.51519775390625,181.414794921875,253.58062744140625,277.0303955078125,310.21270751953125,357.1122131347656,388.1111145019531,411.5609436035156};
    for (size_t i = 0; i < 9; ++i) {
        const auto& r = p.rows[i];
        require(near(number(r,1), gains[i]) && near(number(r,2), ets[i]) && one(r,3,0).value == modes[i] && near(number(r,4),indices[i]), "original nine-row profile does not match researched baseline");
    }
    if (p.rows.size() == 10) {
        const auto& r = p.rows[9]; const double index = number(r,4), oldIndex = number(p.rows[8],4);
        require(near(number(r,1),32) && near(number(r,2),200000000) && one(r,3,0).value == 2 && index > oldIndex && index <= oldIndex + 100, "candidate tenth row invalid");
        Bytes expected = p.rows[8]; putFloat(expected,1,32); putFloat(expected,4,index);
        require(expected == r, "tenth row is not a byte-preserving clone of ninth row");
    }
    return p;
}
static Bytes read(const char* path) {
    FILE* fp = std::fopen(path,"rb"); require(fp != nullptr,"cannot open input");
    Bytes b; uint8_t chunk[4096]; size_t n;
    while ((n = std::fread(chunk,1,sizeof(chunk),fp))) {
        b.insert(b.end(),chunk,chunk+n);
        if (b.size() > 16*1024*1024) { std::fclose(fp); throw std::runtime_error("input exceeds 16 MiB profile limit"); }
    }
    bool ok = !std::ferror(fp); std::fclose(fp); require(ok,"input read failed"); return b;
}
static void writeNew(const char* path, const Bytes& b) {
    // O_EXCL also rejects existing aliases, hardlinks, and symlinks to the input.
    int fd = open(path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600); require(fd >= 0,"output must be a new file (exclusive create failed)");
    size_t p = 0;
    while (p < b.size()) {
        ssize_t n = write(fd,b.data()+p,b.size()-p);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { close(fd); unlink(path); throw std::runtime_error("output write failed; incomplete output removed"); }
        p += size_t(n);
    }
    const int synced = fsync(fd), closed = close(fd);
    if (synced || closed) { unlink(path); throw std::runtime_error("output flush failed; incomplete output removed"); }
}
static void summary(const Bytes& b, const Profile& p) {
    std::cout << std::setprecision(17) << "{\"bytes\":" << b.size() << ",\"path\":\".5.5.1.2\",\"rows\":" << p.rows.size() << ",\"original_nine_validated\":true,\"candidate_only\":" << (p.rows.size()==10?"true":"false") << ",\"loadability_verified\":false,\"last_row\":{\"gain\":" << number(p.rows.back(),1) << ",\"et_ns\":" << number(p.rows.back(),2) << ",\"mode\":" << one(p.rows.back(),3,0).value << ",\"index\":" << number(p.rows.back(),4) << "}}\n";
}
int main(int argc, char** argv) {
    try {
        const bool isInspect = argc == 3 && std::string(argv[1]) == "inspect";
        const bool isPatch = argc == 5 && std::string(argv[1]) == "patch";
        require(isInspect || isPatch,"usage: aon-profile-patcher inspect INPUT | patch INPUT NEW_OUTPUT NEW_INDEX");
        const Bytes original = read(argv[2]); const auto p = inspect(original);
        if (isInspect) { summary(original,p); return 0; }
        require(std::strcmp(argv[2],argv[3]) != 0,"input and output paths must differ");
        require(p.rows.size()==9,"patch requires the original nine-row profile; candidate cannot be patched again");
        errno=0; char* end=nullptr; double index=std::strtod(argv[4],&end);
        const double oldIndex=number(p.rows.back(),4);
        require(end != argv[4] && *end == '\0' && errno != ERANGE && std::isfinite(index) && index > oldIndex && index <= oldIndex+100,"NEW_INDEX must be finite, greater than old index and at most old index + 100");
        Bytes row=p.rows.back(); putFloat(row,1,32); putFloat(row,4,index);
        const auto f=p.rowFields.back(); Bytes inserted=slice(p.table,f.begin,f.keyEnd);
        putVarint(inserted,row.size()); append(inserted,row);
        Bytes table=slice(p.table,0,f.end); append(table,inserted); append(table,slice(p.table,f.end,p.table.size()));
        Bytes output=replaceChild(original,5,replaceChild(p.aec,5,replaceChild(p.block,1,table)));
        const auto checked=inspect(output);
        for(size_t i=0;i<9;++i) require(checked.rows[i]==p.rows[i],"internal verification: original row changed");
        Bytes recovered=checked.table; recovered.erase(recovered.begin()+f.end,recovered.begin()+f.end+inserted.size());
        require(recovered==p.table,"internal verification: unrelated table bytes changed");
        writeNew(argv[3],output); summary(output,checked); return 0;
    } catch(const std::exception& e) { std::cerr << "error: " << e.what() << '\n'; return 1; }
}
