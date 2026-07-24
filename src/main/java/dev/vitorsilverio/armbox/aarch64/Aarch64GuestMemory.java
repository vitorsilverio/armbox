package dev.vitorsilverio.armbox.aarch64;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.HashMap;
import java.util.Map;

/// Espaço de endereçamento flat de 64 bits do processo guest AArch64, paginado em 4 KB — irmão de
/// {@link dev.vitorsilverio.armbox.memory.GuestMemory} (mesma disciplina: acesso a página não
/// mapeada é um erro do guest, {@link Aarch64GuestSegmentationFault}), mas NÃO reaproveitado: a
/// classe de 32 bits usa um array `byte[][]` dimensionado para as `2^32` páginas do espaço de
/// endereço `int` inteiro — um array desse tamanho para `2^64` bytes é impossível; aqui as páginas
/// vivem num `Map<Long, byte[]>` esparso (só as páginas realmente mapeadas existem).
public final class Aarch64GuestMemory implements AddressSpace64 {
    public static final int PAGE_SHIFT = 12;
    public static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final long OFFSET_MASK = PAGE_SIZE - 1;

    private final Map<Long, byte[]> pages = new HashMap<>();

    /// Mapeia (aloca zerado) o intervalo `[address, address+sizeBytes)`, arredondando para
    /// fronteiras de página. Páginas já mapeadas são preservadas.
    public void map(long address, long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0: " + sizeBytes);
        }
        long first = address >>> PAGE_SHIFT;
        long last = (address + sizeBytes - 1) >>> PAGE_SHIFT;
        for (long page = first; page <= last; page++) {
            pages.computeIfAbsent(page, ignored -> new byte[PAGE_SIZE]);
        }
    }

    /// Desmapeia o intervalo (páginas inteiras contidas nele). Acesso posterior falha.
    public void unmap(long address, long sizeBytes) {
        long first = address >>> PAGE_SHIFT;
        long last = (address + sizeBytes - 1) >>> PAGE_SHIFT;
        for (long page = first; page <= last; page++) {
            pages.remove(page);
        }
    }

    /// Retorna `true` se a página do endereço está mapeada.
    public boolean isMapped(long address) {
        return pages.containsKey(address >>> PAGE_SHIFT);
    }

    private byte[] page(long address, String access) {
        byte[] page = pages.get(address >>> PAGE_SHIFT);
        if (page == null) {
            throw new Aarch64GuestSegmentationFault(address, access);
        }
        return page;
    }

    @Override
    public int read8(long address) {
        return page(address, "read8")[(int) (address & OFFSET_MASK)] & 0xFF;
    }

    @Override
    public int read16(long address) {
        int offset = (int) (address & OFFSET_MASK);
        if (offset <= PAGE_SIZE - 2) {
            byte[] page = page(address, "read16");
            return (page[offset] & 0xFF) | ((page[offset + 1] & 0xFF) << 8);
        }
        return read8(address) | (read8(address + 1) << 8);
    }

    @Override
    public int read32(long address) {
        int offset = (int) (address & OFFSET_MASK);
        if (offset <= PAGE_SIZE - 4) {
            byte[] page = page(address, "read32");
            return (page[offset] & 0xFF)
                    | ((page[offset + 1] & 0xFF) << 8)
                    | ((page[offset + 2] & 0xFF) << 16)
                    | ((page[offset + 3] & 0xFF) << 24);
        }
        return read16(address) | (read16(address + 2) << 16);
    }

    @Override
    public void write8(long address, int value) {
        page(address, "write8")[(int) (address & OFFSET_MASK)] = (byte) value;
    }

    @Override
    public void write16(long address, int value) {
        int offset = (int) (address & OFFSET_MASK);
        if (offset <= PAGE_SIZE - 2) {
            byte[] page = page(address, "write16");
            page[offset] = (byte) value;
            page[offset + 1] = (byte) (value >>> 8);
            return;
        }
        write8(address, value);
        write8(address + 1, value >>> 8);
    }

    @Override
    public void write32(long address, int value) {
        int offset = (int) (address & OFFSET_MASK);
        if (offset <= PAGE_SIZE - 4) {
            byte[] page = page(address, "write32");
            page[offset] = (byte) value;
            page[offset + 1] = (byte) (value >>> 8);
            page[offset + 2] = (byte) (value >>> 16);
            page[offset + 3] = (byte) (value >>> 24);
            return;
        }
        write16(address, value);
        write16(address + 2, value >>> 16);
    }

    /// Copia `length` bytes da memória guest a partir de `address`.
    public byte[] readBytes(long address, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) read8(address + i);
        }
        return out;
    }

    /// Escreve `data` na memória guest a partir de `address` (páginas devem estar mapeadas).
    public void writeBytes(long address, byte[] data, int offset, int length) {
        for (int i = 0; i < length; i++) {
            write8(address + i, data[offset + i]);
        }
    }

    /// Lê uma string C (terminada em NUL) da memória guest.
    public String readCString(long address) {
        StringBuilder sb = new StringBuilder();
        for (long i = 0; ; i++) {
            int b = read8(address + i);
            if (b == 0) {
                return sb.toString();
            }
            sb.append((char) b);
        }
    }

    /// Processo user-mode não tem waitstates de barramento.
    @Override
    public boolean providesAccessCycles() {
        return false;
    }

    @Override
    public int accessCycles(long address, int sizeBytes, MemoryAccessType type) {
        return 0;
    }
}
