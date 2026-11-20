#include <cassert>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <future>
#include <iostream>
#include <iomanip>
#include <locale>
#include <new>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

template <typename Char>
void verify_file_buffer(const char *path) {
    using traits = std::char_traits<Char>;
    std::basic_filebuf<Char> file;
    Char buffer[32];
    file.pubsetbuf(buffer, 32);
    assert(file.open(path, std::ios::out | std::ios::binary));
    for (int i = 0; i < 96; ++i) {
        Char value = static_cast<Char>('a' + (i % 26));
        assert(traits::eq_int_type(file.sputc(value), traits::to_int_type(value)));
    }
    assert(file.close());

    Char tail = static_cast<Char>('!');
    file.pubsetbuf(nullptr, 0);
    assert(file.open(path, std::ios::out | std::ios::app | std::ios::binary));
    assert(traits::eq_int_type(file.sputc(tail), traits::to_int_type(tail)));
    assert(file.pubsync() == 0);
    assert(file.close());

    std::basic_ifstream<Char> input(path, std::ios::binary);
    assert(input.is_open());
    for (int i = 0; i < 96; ++i) {
        assert(traits::eq_int_type(input.get(), traits::to_int_type(static_cast<Char>('a' + (i % 26)))));
    }
    assert(traits::eq_int_type(input.get(), traits::to_int_type(tail)));
    assert(traits::eq_int_type(input.get(), traits::eof()));
    input.close();
    assert(std::remove(path) == 0);
}

int main() {
    for (std::size_t alignment : {16, 64, 4096}) {
        void *memory = ::operator new(1024, std::align_val_t(alignment));
        assert(reinterpret_cast<std::uintptr_t>(memory) % alignment == 0);
        ::operator delete(memory, std::align_val_t(alignment));
    }
    bool caught = false;
    const std::string message = "normal exception unwinding";
    try { throw std::runtime_error(message); }
    catch (const std::runtime_error &error) { caught = error.what() == message; }
    assert(caught);
    std::vector<int> values;
    std::thread worker([&values] { values.assign({1, 2, 3}); });
    worker.join();
    assert(values.size() == 3 && values[2] == 3);
    assert(std::async(std::launch::async, [] { return 42; }).get() == 42);
    std::stringstream stream;
    stream.imbue(std::locale::classic());
    stream << std::fixed << std::setprecision(3) << 1.125L;
    assert(stream.str() == "1.125");
    long double round_trip = 0;
    stream >> round_trip;
    assert(stream && round_trip == 1.125L);

    verify_file_buffer<char>("/tmp/testhub-filebuf-char");
    verify_file_buffer<wchar_t>("/tmp/testhub-filebuf-wchar");
    std::cout << "C++ string ABI " << _GLIBCXX_USE_CXX11_ABI
              << ": allocation, exceptions, threads, futures, vector, long double and narrow/wide file buffers passed\n";
}
